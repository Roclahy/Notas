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
    @ColumnInfo(defaultValue = "''") val wallpaperName: String = "",
    /** -1 keeps the original gold folder icon. */
    @ColumnInfo(defaultValue = "-1") val iconColor: Int = -1
)
