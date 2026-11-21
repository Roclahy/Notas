package com.omgodse.notally.backup

import android.app.Application
import android.net.Uri
import android.os.Build
import android.preference.PreferenceManager
import com.omgodse.notally.miscellaneous.Export
import com.omgodse.notally.miscellaneous.IO
import com.omgodse.notally.security.SecureMedia
import com.omgodse.notally.room.Converters
import com.omgodse.notally.room.NotallyDatabase
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Formato RNBACK01: archivo ZIP cifrado localmente con AES-256-GCM.
 *  La contraseña jamás se envía a Drive. No se reutiliza la nonce.
 */
object EncryptedBackups {
    private val magic = "RNBACK01".toByteArray(Charsets.US_ASCII)
    private const val KDF_SHA256 = 1
    private const val KDF_SHA1 = 2
    private const val iterations = 250_000
    private val random = SecureRandom()
    private val settingKeys = setOf(
        "theme", "view", "dateFormat", "textSize",
        "maxItemsToDisplayInList.v1", "maxLinesToDisplayInNote.v1",
        "maxLinesToDisplayInTitle"
    )

    fun isEncrypted(app: Application, uri: Uri): Boolean {
        val prefix = ByteArray(magic.size)
        return try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                DataInputStream(input).readFully(prefix)
                prefix.contentEquals(magic)
            } ?: false
        } catch (_: Exception) { false }
    }

    private fun key(passphrase: CharArray, salt: ByteArray, kdf: Int): SecretKeySpec {
        val algorithm = when (kdf) {
            KDF_SHA256 -> "PBKDF2WithHmacSHA256"
            KDF_SHA1 -> "PBKDF2WithHmacSHA1"
            else -> throw java.io.IOException("Unsupported backup KDF")
        }
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val bytes = try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val key = SecretKeySpec(bytes, "AES")
        bytes.fill(0)
        return key
    }

    fun write(app: Application, output: OutputStream, password: CharArray) {
        require(password.size >= 12) { "Backup password requires at least 12 characters" }
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val kdf = if (Build.VERSION.SDK_INT >= 26) KDF_SHA256 else KDF_SHA1
        val header = DataOutputStream(output)
        header.write(magic)
        header.writeByte(kdf)
        header.write(salt)
        header.write(iv)
        header.flush()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, kdf), GCMParameterSpec(128,iv))
        cipher.updateAAD(magic)
        ZipOutputStream(CipherOutputStream(output, cipher)).use { zip ->
            populateZip(app, zip)
        }
    }

    fun decryptToCache(app: Application, input: InputStream, password: CharArray): File {
        val stream = DataInputStream(input)
        val receivedMagic = ByteArray(magic.size)
        stream.readFully(receivedMagic)
        require(receivedMagic.contentEquals(magic)) { "Invalid encrypted backup" }
        val kdf = stream.readUnsignedByte()
        val salt = ByteArray(16); stream.readFully(salt)
        val iv = ByteArray(12); stream.readFully(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password,salt,kdf), GCMParameterSpec(128,iv))
        cipher.updateAAD(magic)
        val folder = File(app.cacheDir, "encrypted_restore").apply { mkdirs() }
        val dest = File(folder, "restored_${java.util.UUID.randomUUID()}.zip")
        try {
            CipherInputStream(stream, cipher).use { decoded ->
                FileOutputStream(dest).use { out -> decoded.copyTo(out) }
            }
            // Leer hasta el final verifica la etiqueta GCM; no importar un ZIP parcial.
            ZipFile(dest).use { archive ->
                requireNotNull(archive.getEntry(NotallyDatabase.DatabaseName)) { "Missing notes" }
            }
            return dest
        } catch (error: Exception) {
            dest.delete()
            throw error
        }
    }

    private fun populateZip(app: Application, zip: ZipOutputStream) {
        val database = NotallyDatabase.getDatabase(app)
        database.checkpoint()
        Export.backupDatabase(app, zip)
        val images = IO.getExternalImagesDirectory(app)
        val audios = IO.getExternalAudioDirectory(app)
        database.getBaseNoteDao().getAllImages().asSequence()
            .flatMap { Converters.jsonToImages(it) }.distinctBy { it.name }
            .forEach { SecureMedia.backupFile(app, zip, images,"Images",it.name) }
        database.getBaseNoteDao().getAllAudios().asSequence()
            .flatMap { Converters.jsonToAudios(it) }.distinctBy { it.name }
            .forEach { SecureMedia.backupFile(app, zip, audios,"Audios",it.name) }

        val wallpaperRoot = File(app.filesDir, "SecureMedia/Wallpapers")
        database.getBaseNoteDao().getAllWallpapers().asSequence().distinct()
            .forEach { SecureMedia.backupFile(app, zip, wallpaperRoot, "Wallpapers", it) }

        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val settings = Properties()
        for (setting in settingKeys) {
            prefs.all[setting]?.let { settings.setProperty(setting, it.toString()) }
        }
        zip.putNextEntry(ZipEntry("Notas/settings.properties"))
        settings.store(zip, "Notas settings (no PIN or backup credentials)")
        zip.closeEntry()
    }

    fun restoreSettings(app: Application, archive: File) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry("Notas/settings.properties") ?: return
            val props = Properties()
            zip.getInputStream(entry).use(props::load)
            val edit = prefs.edit()
            for (key in settingKeys) {
                val value = props.getProperty(key) ?: continue
                if (key in setOf("maxItemsToDisplayInList.v1",
                    "maxLinesToDisplayInNote.v1", "maxLinesToDisplayInTitle")) {
                    value.toIntOrNull()?.takeIf { it in 1..100 }?.let { edit.putInt(key,it) }
                } else {
                    if (value.length <= 40) edit.putString(key, value)
                }
            }
            edit.commit()
        }
        com.omgodse.notally.preferences.Preferences.getInstance(app).reloadAfterBackupRestore()
    }
}
