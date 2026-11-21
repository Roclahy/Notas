package com.omgodse.notally.security

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import com.omgodse.notally.room.NotallyDatabase
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Room/SQLite indexa relaciones y metadatos técnicos.
 * Todos los campos sensibles se protegen con AES-GCM por campo.
 * No depende de SQLCipher ni requiere binarios criptográficos externos.
 */
object SecureDatabase {
    private const val marker = "native_fields_sealed_v1"

    fun verifyCompatibleHeader(app: Application, name: String) {
        val file = app.getDatabasePath(name)
        if (!file.exists() || file.length() < 16) return
        val magic = ByteArray(16)
        file.inputStream().use { input ->
            check(input.read(magic) == 16) { "Invalid database header" }
        }
        check(magic.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) {
            // No se descarta una base antigua. Para ese caso, la persona
            // debe desinstalar la instalación anterior sin datos o restaurar
            // desde una copia RNBACK01 previa.
            "Legacy SQLCipher database detected. Do not overwrite; recover first."
        }
    }

    /**
     * Una migración de SQLite antiguo sin cifrado de campos, idempotente.
     * La transacción garantiza que no queda una parte de las filas en claro
     * si ocurre un fallo antes de confirmar.
     */
    fun encryptExistingPlaintext(app: Application, room: NotallyDatabase) {
        val preferences = app.getSharedPreferences("rose-native-db", 0)
        if (preferences.getBoolean(marker, false)) return
        val db = room.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.query("SELECT id,title,body,labels,spans,items,images,audios,reminder FROM BaseNote")
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        val fields = arrayOf("title","body","labels","spans","items","images","audios","reminder")
                        val values = Array<Any?>(fields.size) { i ->
                            if (cursor.isNull(i+1)) null else {
                                val source = cursor.getString(i+1)
                                if (SecureFields.isSealed(source)) source
                                else SecureFields.seal(source, fields[i])
                            }
                        }
                        db.execSQL("""
                            UPDATE BaseNote SET title=?,body=?,labels=?,spans=?,items=?,
                                images=?,audios=?,reminder=? WHERE id=?
                        """.trimIndent(), values.toList().plus(cursor.getLong(0)).toTypedArray())
                    }
                }
            db.query("SELECT value FROM Label").use { cursor ->
                while (cursor.moveToNext()) {
                    val text = cursor.getString(0)
                    if (!SecureFields.isSealed(text)) {
                        db.execSQL("UPDATE Label SET value=? WHERE value=?",
                            arrayOf(SecureFields.seal(text,"label"), text))
                    }
                }
            }
            db.query("SELECT id,name,passwordHash FROM NotebookFolder").use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1)
                    val hash = cursor.getString(2)
                    db.execSQL("UPDATE NotebookFolder SET name=?,passwordHash=? WHERE id=?",
                        arrayOf(
                            if (SecureFields.isSealed(name)) name else SecureFields.seal(name,"folderName"),
                            if (SecureFields.isSealed(hash)) hash else SecureFields.seal(hash,"folderHash"),
                            id))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        check(preferences.edit().putBoolean(marker,true).commit()) { "Failed to save field encryption migration state" }
    }

    /**
     * ZIP de Drive: SQLite portable descifrado solo dentro de caché privada
     * durante el streaming al formato RNBACK01 cifrado con contraseña.
     */
    fun writePortableBackup(app: Application, room: NotallyDatabase, zip: ZipOutputStream) {
        val portable = File.createTempFile("rose_portable_", ".db", app.cacheDir)
        try {
            // El respaldo es snapshot coherente (VACUUM INTO), sin WAL colgante.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                portable.delete()
                val escaped = portable.absolutePath.replace("'", "''")
                room.openHelper.writableDatabase.execSQL("VACUUM INTO '$escaped'")
            } else {
                room.checkpoint()
                app.getDatabasePath(NotallyDatabase.DatabaseName).copyTo(portable, overwrite = true)
            }
            val sqlite = SQLiteDatabase.openDatabase(portable.absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE)
            try {
                sqlite.beginTransaction()
                try {
                    sqlite.rawQuery(
                        "SELECT id,title,body,labels,spans,items,images,audios,reminder FROM BaseNote",
                        null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val cols = arrayOf("title","body","labels","spans","items","images","audios","reminder")
                            val values = Array<Any?>(cols.size) { i ->
                                if (cursor.isNull(i+1)) null
                                else SecureFields.open(cursor.getString(i+1), cols[i])
                            }
                            sqlite.execSQL("UPDATE BaseNote SET title=?,body=?,labels=?,spans=?,items=?,images=?,audios=?,reminder=? WHERE id=?",
                                values.toList().plus(cursor.getLong(0)).toTypedArray())
                        }
                    }
                    sqlite.rawQuery("SELECT value FROM Label",null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val old = cursor.getString(0)
                            sqlite.execSQL("UPDATE Label SET value=? WHERE value=?",
                                arrayOf(SecureFields.open(old,"label"),old))
                        }
                    }
                    sqlite.rawQuery("SELECT id,name,passwordHash FROM NotebookFolder",null).use { cursor ->
                        while (cursor.moveToNext()) {
                            sqlite.execSQL("UPDATE NotebookFolder SET name=?,passwordHash=? WHERE id=?",
                                arrayOf(
                                    SecureFields.open(cursor.getString(1),"folderName"),
                                    SecureFields.open(cursor.getString(2),"folderHash"),
                                    cursor.getLong(0)))
                        }
                    }
                    sqlite.setTransactionSuccessful()
                } finally { sqlite.endTransaction() }
                sqlite.rawQuery("PRAGMA wal_checkpoint(FULL)",null).use { it.moveToFirst() }
            } finally { sqlite.close() }
            zip.putNextEntry(ZipEntry(NotallyDatabase.DatabaseName))
            FileInputStream(portable).use { it.copyTo(zip) }
            zip.closeEntry()
        } finally {
            portable.delete()
            File(portable.absolutePath + "-wal").delete()
            File(portable.absolutePath + "-shm").delete()
        }
    }
}
