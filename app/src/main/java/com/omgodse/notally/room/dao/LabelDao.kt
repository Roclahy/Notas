package com.omgodse.notally.room.dao

import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.omgodse.notally.room.Label
import com.omgodse.notally.security.SecureFields

@Dao
abstract class LabelDao {
    private fun Label.sealed() = Label(SecureFields.seal(value, "label"))
    private fun String.opened() = SecureFields.open(this, "label")

    @Insert
    abstract suspend fun rawInsert(label: Label)
    suspend fun insert(label: Label) = rawInsert(label.sealed())

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun rawInsert(labels: List<Label>)
    suspend fun insert(labels: List<Label>) = rawInsert(labels.map { it.sealed() })

    @Query("DELETE FROM Label WHERE value = :stored")
    abstract suspend fun rawDelete(stored: String)
    suspend fun delete(value: String) {
        val match = rawValues().firstOrNull { it.opened() == value } ?: return
        rawDelete(match)
    }

    @Query("UPDATE Label SET value = :newStored WHERE value = :oldStored")
    abstract suspend fun rawUpdate(oldStored: String, newStored: String)
    suspend fun update(oldValue: String, newValue: String) {
        val stored = rawValues().firstOrNull { it.opened() == oldValue } ?: return
        rawUpdate(stored, SecureFields.seal(newValue, "label"))
    }

    @Query("SELECT value FROM Label ORDER BY value")
    abstract fun rawLiveValues(): LiveData<List<String>>
    fun getAll(): LiveData<List<String>> =
        Transformations.map(rawLiveValues()) { values ->
            values.map { it.opened() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        }

    @Query("SELECT value FROM Label ORDER BY value")
    abstract suspend fun rawValues(): List<String>
    suspend fun getArrayOfAll(): Array<String> =
        rawValues().map { it.opened() }.sortedWith(String.CASE_INSENSITIVE_ORDER).toTypedArray()
}
