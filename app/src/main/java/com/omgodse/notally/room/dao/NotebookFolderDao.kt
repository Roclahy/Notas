package com.omgodse.notally.room.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.omgodse.notally.room.NotebookFolder

@Dao
interface NotebookFolderDao {
    @Query("SELECT * FROM NotebookFolder ORDER BY name COLLATE NOCASE")
    fun getAll(): LiveData<List<NotebookFolder>>

    @Query("SELECT * FROM NotebookFolder ORDER BY name COLLATE NOCASE")
    suspend fun listOnce(): List<NotebookFolder>

    @Insert
    suspend fun insert(folder: NotebookFolder): Long

    @Query("SELECT * FROM NotebookFolder ORDER BY id")
    fun listSnapshot(): List<NotebookFolder>

    @Query("SELECT * FROM NotebookFolder WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): NotebookFolder?

    @Query("SELECT * FROM NotebookFolder WHERE id = :id LIMIT 1")
    fun getNow(id: Long): NotebookFolder?

    @Query("DELETE FROM NotebookFolder WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE BaseNote SET notebookId = 0 WHERE notebookId = :id")
    suspend fun unfile(id: Long)
}
