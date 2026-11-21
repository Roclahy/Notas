package com.omgodse.notally.security

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.sqlite.db.SimpleSQLiteQuery
import com.omgodse.notally.room.NotallyDatabase
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Clave local aleatoria de 256 bits, envuelta con Android Keystore.
 * Migración transaccional de SQLite a SQLCipher; sin borrar la DB previa
 * hasta que Room haya abierto y validado la nueva.
 */
object SecureDatabase {
    private const val alias = "rocla.notas.room.master.v1"
    private const val preferences = "rocla.notas.db.v1"
    private val random = SecureRandom()

    private fun wrapper(): SecretKey {
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

    fun databasePassphrase(app: Application): String {
        val prefs = app.getSharedPreferences(preferences, Context.MODE_PRIVATE)
        val iv64 = prefs.getString("iv", null)
        val wrapped64 = prefs.getString("wrapped", null)
        if (iv64 != null && wrapped64 != null) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrapper(),
                GCMParameterSpec(128, Base64.decode(iv64, Base64.NO_WRAP)))
            val plaintext = cipher.doFinal(Base64.decode(wrapped64, Base64.NO_WRAP))
            return try { String(plaintext, Charsets.US_ASCII) }
            finally { plaintext.fill(0) }
        }
        // No generar otra clave si solo queda la mitad del registro.
        require(iv64 == null && wrapped64 == null) { "Database key incomplete" }
        val secret = ByteArray(32).also(random::nextBytes)
        val passphrase = Base64.encodeToString(secret, Base64.NO_WRAP)
        secret.fill(0)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapper())
        val wrapped = cipher.doFinal(passphrase.toByteArray(Charsets.US_ASCII))
        check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("wrapped", Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit())
        wrapped.fill(0)
        return passphrase
    }

    private fun isPlaintext(file: File): Boolean {
        if (!file.exists() || file.length() < 16L) return false
        return file.inputStream().use {
            val header = ByteArray(16)
            it.read(header) == header.size &&
                header.copyOfRange(0, 15)
                    .contentEquals("SQLite format 3".toByteArray(Charsets.US_ASCII))
        }
    }

    fun migrateLegacy(app: Application, databaseName: String, password: String) {
        val original = app.getDatabasePath(databaseName)
        if (!isPlaintext(original)) return

        // El exportador lee el origen SQLite con KEY '' y escribe un destino
        // cifrado. La DB original y sus WAL no se eliminan durante la copia.
        original.parentFile?.mkdirs()
        val pending = File(original.parentFile, "$databaseName.sealed.pending")
        pending.delete()
        val legacy = File(original.parentFile, "$databaseName.plaintext.migration")
        require(!legacy.exists()) { "Prior plaintext migration must be recovered first" }

        // Garantiza que el WAL anterior esté integrado antes de copiar.
        android.database.sqlite.SQLiteDatabase.openDatabase(
            original.path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            sqlite.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        }

        var cipher: SQLiteDatabase? = null
        try {
            cipher = SQLiteDatabase.openOrCreateDatabase(pending, password, null, null, null)
            val escaped = original.absolutePath.replace("'", "''")
            cipher.rawExecSQL("ATTACH DATABASE '$escaped' AS plaintext KEY ''")
            try {
                cipher.rawQuery("SELECT sqlcipher_export('main', 'plaintext')", null)
                    .use { require(it.moveToFirst()) }
                val version = android.database.sqlite.SQLiteDatabase.openDatabase(
                    original.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
                    .use { db -> db.version }
                cipher.rawExecSQL("PRAGMA user_version = $version")
            } finally {
                cipher.rawExecSQL("DETACH DATABASE plaintext")
            }
            cipher.close()
            cipher = null
            // Verificar el destino antes de mover un byte del origen.
            SQLiteDatabase.openOrCreateDatabase(pending, password, null, null, null).use { check ->
                check.rawQuery("SELECT count(*) FROM BaseNote", emptyArray()).use {
                    require(it.moveToFirst())
                }
            }
            check(original.renameTo(legacy)) { "Could not preserve legacy database" }
            if (!pending.renameTo(original)) {
                legacy.renameTo(original)
                error("Could not activate encrypted database")
            }
            File(original.path + "-wal").delete()
            File(original.path + "-shm").delete()
        } catch (error: Exception) {
            pending.delete()
            throw error
        } finally { cipher?.close() }
    }

    /** Si Room rechaza el esquema, restaura el SQLite anterior sin perder datos. */
    fun rollbackFailedMigration(app: Application, databaseName: String) {
        val original = app.getDatabasePath(databaseName)
        val legacy = File(original.parentFile, "$databaseName.plaintext.migration")
        if (!legacy.isFile) return
        val failed = File(original.parentFile, "$databaseName.failed.sealed")
        if (original.isFile) {
            if (!original.renameTo(failed)) return
        }
        if (!legacy.renameTo(original)) {
            failed.renameTo(original)
        } else failed.delete()
        File(original.path + "-wal").delete()
        File(original.path + "-shm").delete()
    }

    fun finishMigration(app: Application, databaseName: String) {
        val original = app.getDatabasePath(databaseName)
        val legacy = File(original.parentFile, "$databaseName.plaintext.migration")
        if (original.isFile && !isPlaintext(original)) legacy.delete()
    }

    /**
     * Copia de Drive interoperable: exporta temporalmente un SQLite portable,
     * que entra inmediatamente en el flujo ZIP cifrado AES-GCM (RNBACK01).
     * Nunca se sube el SQLite descifrado a Google.
     */
    fun writePortableBackup(app: Application, db: NotallyDatabase, zip: ZipOutputStream) {
        val portable = File.createTempFile("notas_portable_", ".db", app.cacheDir)
        portable.delete()
        val sql = db.openHelper.writableDatabase
        val escaped = portable.absolutePath.replace("'", "''")
        try {
            sql.execSQL("ATTACH DATABASE '$escaped' AS portable KEY ''")
            try {
                sql.query(SimpleSQLiteQuery("SELECT sqlcipher_export('portable')")).use {
                    require(it.moveToFirst())
                }
                sql.query(SimpleSQLiteQuery("PRAGMA user_version")).use { version ->
                    if (version.moveToFirst()) {
                        sql.execSQL("PRAGMA portable.user_version = ${version.getInt(0)}")
                    }
                }
            } finally {
                sql.execSQL("DETACH DATABASE portable")
            }
            FileInputStream(portable).use { stream ->
                zip.putNextEntry(ZipEntry(NotallyDatabase.DatabaseName))
                stream.copyTo(zip)
                zip.closeEntry()
            }
        } finally { portable.delete() }
    }
}
