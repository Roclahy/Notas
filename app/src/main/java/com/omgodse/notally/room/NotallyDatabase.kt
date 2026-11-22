package com.omgodse.notally.room

import android.app.Application
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.omgodse.notally.room.dao.BaseNoteDao
import com.omgodse.notally.room.dao.CommonDao
import com.omgodse.notally.room.dao.LabelDao
import com.omgodse.notally.room.dao.NotebookFolderDao
import com.omgodse.notally.security.SecureDatabase
import com.omgodse.notally.security.SecureFields

@TypeConverters(Converters::class)
@Database(entities = [BaseNote::class, Label::class, NotebookFolder::class], version = 9)
abstract class NotallyDatabase : RoomDatabase() {

    abstract fun getLabelDao(): LabelDao
    abstract fun getCommonDao(): CommonDao
    abstract fun getBaseNoteDao(): BaseNoteDao
    abstract fun getNotebookFolderDao(): NotebookFolderDao

    fun checkpoint() {
        getBaseNoteDao().query(SimpleSQLiteQuery("pragma wal_checkpoint(FULL)"))
    }

    companion object {

        const val DatabaseName = "NotallyDatabase"

        @Volatile
        private var instance: NotallyDatabase? = null

        /**
         * allowMainThreadQueries() is only used in [com.omgodse.notally.ReminderReceiver.onReceive]
         * when an alarm fires off!
         */
        fun getDatabase(app: Application): NotallyDatabase {
            return instance ?: synchronized(this) {
                // En una instalación nueva: Room + Android Keystore, sin JNI.
                SecureFields.initialize(app)
                SecureDatabase.verifyCompatibleHeader(app, DatabaseName)
                val opened = Room.databaseBuilder(app, NotallyDatabase::class.java, DatabaseName)
                    .addMigrations(Migration2, Migration3, Migration4, Migration5, Migration6, Migration7, Migration8, Migration9)
                    .allowMainThreadQueries()
                    .build()
                opened.openHelper.writableDatabase // Validar Room antes de usarla.
                SecureDatabase.encryptExistingPlaintext(app, opened)
                this.instance = opened
                return opened
            }
        }

        object Migration2 : Migration(1, 2) {

            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `BaseNote` ADD COLUMN `color` TEXT NOT NULL DEFAULT 'DEFAULT'")
            }
        }

        object Migration3 : Migration(2, 3) {

            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `BaseNote` ADD COLUMN `images` TEXT NOT NULL DEFAULT `[]`")
            }
        }

        object Migration4 : Migration(3, 4) {

            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `BaseNote` ADD COLUMN `audios` TEXT NOT NULL DEFAULT `[]`")
            }
        }

        object Migration9 : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE NotebookFolder ADD COLUMN iconColor INTEGER NOT NULL DEFAULT -1")
            }
        }

        object Migration8 : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE NotebookFolder ADD COLUMN wallpaperName TEXT NOT NULL DEFAULT ''")
            }
        }

        object Migration7 : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE BaseNote ADD COLUMN wallpaperName TEXT NOT NULL DEFAULT ''")
            }
        }

        object Migration6 : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE BaseNote ADD COLUMN notebookId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS NotebookFolder (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        passwordHash TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        object Migration5 : Migration(4, 5) {

            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `BaseNote` ADD COLUMN `reminder` TEXT")
            }
        }
    }
}