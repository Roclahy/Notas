package com.omgodse.notally.security

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifrado en reposo de fotos y grabaciones con AES-256-GCM.
 * La clave no exportable vive en Android Keystore. El ZIP de Drive descifra
 * únicamente dentro del flujo ya cifrado RNBACK01; al restaurar se cifra
 * con una clave nueva del dispositivo.
 */
object SecureMedia {
    private val magic = "RMEDIA01".toByteArray(Charsets.US_ASCII)
    private const val alias = "rocla.notas.media.aes.v1"
    private val random = SecureRandom()
    private val lock = Any()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    private fun root(app: Application, kind: String): File =
        File(app.filesDir, "SecureMedia/$kind").apply { mkdirs() }

    /** Cifra un stream, sin materializar datos legibles en el destino. */
    fun encryptFromStream(app: Application, input: InputStream, destination: File) {
        synchronized(lock) {
            require(destination.canonicalPath.startsWith(
                File(app.filesDir, "SecureMedia").canonicalPath + File.separator))
            destination.parentFile?.mkdirs()
            val temp = File(destination.parentFile, destination.name + ".writing")
            try {
                val iv = ByteArray(12).also(random::nextBytes)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
                cipher.updateAAD(magic)
                FileOutputStream(temp).use { output ->
                    output.write(magic)
                    output.write(iv)
                    CipherOutputStream(output, cipher).use { encryptedOut ->
                        input.copyTo(encryptedOut, 64 * 1024)
                    }
                }
                // Nunca reemplazar el original antes de haber finalizado GCM.
                if (!temp.renameTo(destination)) {
                    if (!destination.delete() || !temp.renameTo(destination)) {
                        throw java.io.IOException("Cannot commit encrypted media")
                    }
                }
            } finally {
                temp.delete()
                input.close()
            }
        }
    }

    /** Solo se utiliza durante la transición de un fichero recién creado. */
    fun encryptInPlace(app: Application, plaintext: File) {
        if (!plaintext.isFile) return
        val incoming = File(plaintext.parentFile, plaintext.name + ".plain")
        check(plaintext.renameTo(incoming)) { "Cannot protect attachment" }
        try {
            incoming.inputStream().use { encryptFromStream(app, it, plaintext) }
        } catch (error: Exception) {
            // Conservar el original si el cifrado falla.
            if (!plaintext.exists()) incoming.renameTo(plaintext)
            throw error
        } finally {
            if (isEncryptedFile(plaintext)) incoming.delete()
        }
    }

    fun openDecrypted(app: Application, file: File): InputStream {
        val source = if (file.exists()) file else legacyPath(app, file)
        val input = FileInputStream(source)
        try {
            val prefix = ByteArray(magic.size)
            if (input.read(prefix) != prefix.size || !prefix.contentEquals(magic)) {
                input.close()
                return source.inputStream() // medio anterior a la migración
            }
            val iv = ByteArray(12)
            if (input.read(iv) != iv.size) throw java.io.IOException("Invalid media header")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.updateAAD(magic)
            return CipherInputStream(input, cipher)
        } catch (error: Exception) {
            input.close()
            throw error
        }
    }

    private fun legacyPath(app: Application, file: File): File {
        val kind = file.parentFile?.name ?: ""
        require(kind == "Images" || kind == "Audios")
        val mediaRoot = app.externalMediaDirs.firstOrNull()
            ?: throw java.io.FileNotFoundException(file.name)
        val old = File(File(mediaRoot, kind), file.name)
        if (!old.isFile) throw java.io.FileNotFoundException(file.name)
        return old
    }

    /** Temporal legible solo mientras se visualiza/reproduce o comparte. */
    fun readableFile(app: Application, file: File): File {
        synchronized(lock) {
            val directory = File(app.cacheDir, "secure_view/${file.parentFile?.name}")
                .apply { mkdirs() }
            val output = File(directory, file.name)
            val source = if (file.exists()) file else legacyPath(app, file)
            if (!output.exists() || output.lastModified() < source.lastModified()) {
                val part = File(directory, file.name + ".part")
                try {
                    openDecrypted(app, file).use { input ->
                        FileOutputStream(part).use { input.copyTo(it) }
                    }
                    if (!part.renameTo(output)) {
                        part.copyTo(output, overwrite = true)
                        part.delete()
                    }
                } finally { part.delete() }
            }
            return output
        }
    }

    fun backupFile(app: Application, zip: ZipOutputStream, root: File?,
                   category: String, name: String) {
        if (root == null) return
        val path = File(root, name)
        try {
            openDecrypted(app, path).use { input ->
                zip.putNextEntry(ZipEntry("$category/$name"))
                input.copyTo(zip, 64 * 1024)
                zip.closeEntry()
            }
        } catch (missing: java.io.FileNotFoundException) {
            // Un adjunto eliminado fuera de la app no invalida el respaldo.
        }
    }

    /** Comprobar autenticidad GCM completa antes de eliminar un original. */
    private fun isEncryptedFile(file: File): Boolean {
        if (!file.isFile || file.length() <= 40) return false
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(magic.size)
                if (input.read(header) != header.size || !header.contentEquals(magic))
                    return false
                val iv = ByteArray(12)
                if (input.read(iv) != iv.size) return false
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                cipher.updateAAD(magic)
                CipherInputStream(input, cipher).use { decoded ->
                    val sink = ByteArray(64 * 1024)
                    while (decoded.read(sink) != -1) Unit
                }
            }
            true
        } catch (_: Exception) { false }
    }

    fun clearReadableCache(app: Application) {
        File(app.cacheDir, "secure_view").deleteRecursively()
    }

    fun recoverInterruptedWrites(app: Application) {
        for (kind in arrayOf("Images", "Audios", "Wallpapers")) {
            val directory = root(app, kind)
            directory.listFiles()?.forEach { file ->
                if (file.name.endsWith(".writing")) {
                    file.delete() // Nunca publicar un GCM parcial.
                } else if (file.name.endsWith(".plain")) {
                    val dest = File(directory, file.name.removeSuffix(".plain"))
                    try {
                        if (!dest.exists()) {
                            file.inputStream().use { input ->
                                encryptFromStream(app, input, dest)
                            }
                        }
                        if (isEncryptedFile(dest)) file.delete()
                    } catch (_: Exception) {
                        // Mantener el original plaintext hasta validar AES-GCM.
                    }
                }
            }
        }
    }

    fun migrateLegacy(app: Application) {
        val mediaRoot = app.externalMediaDirs.firstOrNull() ?: return
        for (kind in arrayOf("Images", "Audios")) {
            val oldDirectory = File(mediaRoot, kind)
            val target = root(app, kind)
            oldDirectory.listFiles()?.filter { it.isFile && it.name != "Temp" }
                ?.forEach { old ->
                    val destination = File(target, old.name)
                    if (!destination.exists()) {
                        try {
                            old.inputStream().use { encryptFromStream(app, it, destination) }
                        } catch (_: Exception) { return@forEach }
                    }
                    if (isEncryptedFile(destination)) old.delete()
                }
        }
    }
}
