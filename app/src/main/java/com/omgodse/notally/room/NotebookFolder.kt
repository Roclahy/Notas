package com.omgodse.notally.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Carpetas de usuario, separadas de NOTES/ARCHIVED/DELETED. */
@Entity
data class NotebookFolder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val passwordHash: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val wallpaperName: String = ""
)
