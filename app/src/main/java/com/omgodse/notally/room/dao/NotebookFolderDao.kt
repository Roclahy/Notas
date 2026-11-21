package com.omgodse.notally.room.dao

import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.omgodse.notally.room.NotebookFolder
import com.omgodse.notally.security.SecureFields

@Dao
abstract class NotebookFolderDao {
    private fun NotebookFolder.sealed() = copy(
        name = SecureFields.seal(name, "folderName"),
        passwordHash = SecureFields.seal(passwordHash, "folderHash"))
    private fun NotebookFolder.opened() = copy(
        name = SecureFields.open(name, "folderName"),
        passwordHash = SecureFields.open(passwordHash, "folderHash"))
    private fun List<NotebookFolder>.opened(): List<NotebookFolder> =
        map { it.opened() }.sortedBy { it.name.lowercase() }

    @Query("SELECT * FROM NotebookFolder ORDER BY name COLLATE NOCASE")
    abstract fun rawLive(): LiveData<List<NotebookFolder>>
    fun getAll(): LiveData<List<NotebookFolder>> =
        Transformations.map(rawLive()) { it.opened() }

    @Query("SELECT * FROM NotebookFolder ORDER BY id")
    abstract suspend fun rawList(): List<NotebookFolder>
    suspend fun listOnce(): List<NotebookFolder> = rawList().opened()

    @Insert
    abstract suspend fun rawInsert(folder: NotebookFolder): Long
    suspend fun insert(folder: NotebookFolder): Long = rawInsert(folder.sealed())

    @Query("SELECT * FROM NotebookFolder ORDER BY id")
    abstract fun rawSnapshot(): List<NotebookFolder>
    fun listSnapshot(): List<NotebookFolder> = rawSnapshot().opened()

    @Query("SELECT * FROM NotebookFolder WHERE id = :id LIMIT 1")
    abstract suspend fun rawGet(id: Long): NotebookFolder?
    suspend fun get(id: Long): NotebookFolder? = rawGet(id)?.opened()

    @Query("SELECT * FROM NotebookFolder WHERE id = :id LIMIT 1")
    abstract fun rawGetNow(id: Long): NotebookFolder?
    fun getNow(id: Long): NotebookFolder? = rawGetNow(id)?.opened()

    @Query("DELETE FROM NotebookFolder WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("UPDATE BaseNote SET notebookId = 0 WHERE notebookId = :id")
    abstract suspend fun unfile(id: Long)
}
