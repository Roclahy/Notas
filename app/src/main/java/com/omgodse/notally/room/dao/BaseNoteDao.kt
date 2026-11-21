package com.omgodse.notally.room.dao

import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.omgodse.notally.room.Audio
import com.omgodse.notally.room.BaseNote
import com.omgodse.notally.room.Color
import com.omgodse.notally.room.Folder
import com.omgodse.notally.room.IdReminder
import com.omgodse.notally.room.Image
import com.omgodse.notally.room.ListItem
import com.omgodse.notally.room.Reminder
import com.omgodse.notally.room.SpanRepresentation
import com.omgodse.notally.security.SecureFields

/**
 * Índice local de vínculos: evita descifrar el cuerpo y los adjuntos de
 * todas las notas para mostrar el selector o las referencias entrantes.
 */
data class NoteLinkIndexRow(
    val id: Long,
    val title: String,
    val spans: List<SpanRepresentation>
)


@Dao
abstract class BaseNoteDao {
    private fun BaseNote.sealed() = copy(
        title = SecureFields.seal(title, "title"),
        body = SecureFields.seal(body, "body"))
    private fun BaseNote.opened() = copy(
        title = SecureFields.open(title, "title"),
        body = SecureFields.open(body, "body"))
    private fun List<BaseNote>.opened() = map { it.opened() }


    @Query("SELECT id,title,spans FROM BaseNote WHERE folder = 'NOTES' ORDER BY timestamp DESC")
    abstract fun getLinkIndexStored(): List<NoteLinkIndexRow>

    fun getLinkIndex(): List<NoteLinkIndexRow> =
        getLinkIndexStored().map { it.copy(title = SecureFields.open(it.title, "title")) }

    @RawQuery
    abstract fun query(query: SupportSQLiteQuery): Int


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertStored(baseNote: BaseNote): Long
    suspend fun insert(baseNote: BaseNote): Long = insertStored(baseNote.sealed())

    @Insert
    abstract suspend fun insertStored(baseNotes: List<BaseNote>): List<Long>
    suspend fun insert(baseNotes: List<BaseNote>): List<Long> =
        insertStored(baseNotes.map { it.sealed() })


    @Query(
        "INSERT INTO BaseNote (type, folder, color, title, pinned, timestamp, labels, body, spans, items, images, audios, notebookId, wallpaperName)\n" +
                "SELECT type, folder, color, title, pinned, timestamp, labels, body, spans, items, images, audios, notebookId, wallpaperName\n" +
                "FROM BaseNote WHERE id IN (:ids)"
    )
    abstract suspend fun copy(ids: LongArray)


    @Query("DELETE FROM BaseNote WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("DELETE FROM BaseNote WHERE id IN (:ids)")
    abstract suspend fun delete(ids: LongArray)

    @Query("DELETE FROM BaseNote WHERE folder = :folder")
    abstract suspend fun deleteFrom(folder: Folder)


    // La vista general no muestra el contenido de carpetas protegidas.
    @Query("""SELECT * FROM BaseNote
        WHERE folder = :folder AND (
            (:notebookId = -1 AND (notebookId = 0 OR notebookId NOT IN
                (SELECT id FROM NotebookFolder WHERE passwordHash != '')))
            OR (notebookId = :notebookId AND :notebookId != -1)
        ) ORDER BY pinned DESC, timestamp DESC""")
    abstract fun getFromNotebookStored(folder: Folder, notebookId: Long): LiveData<List<BaseNote>>

    fun getFromNotebook(folder: Folder, notebookId: Long): LiveData<List<BaseNote>> =
        Transformations.map(getFromNotebookStored(folder, notebookId)) { it.opened() }

    @Query("UPDATE BaseNote SET notebookId = :notebookId WHERE id = :id")
    abstract suspend fun moveToNotebook(id: Long, notebookId: Long)

    @Query("""SELECT * FROM BaseNote WHERE folder = :folder AND
        (notebookId = 0 OR notebookId NOT IN
            (SELECT id FROM NotebookFolder WHERE passwordHash != ''))
        ORDER BY pinned DESC, timestamp DESC""")
    abstract fun getFromStored(folder: Folder): LiveData<List<BaseNote>>

    fun getFrom(folder: Folder): LiveData<List<BaseNote>> =
        Transformations.map(getFromStored(folder)) { it.opened() }

    @Query("""SELECT * FROM BaseNote WHERE folder = 'NOTES'
        AND (notebookId = 0 OR notebookId NOT IN
             (SELECT id FROM NotebookFolder WHERE passwordHash != ''))
        ORDER BY pinned DESC, timestamp DESC""")
    abstract suspend fun getAllNotesStored(): List<BaseNote>

    suspend fun getAllNotes(): List<BaseNote> = getAllNotesStored().opened()

    @Query("SELECT * FROM BaseNote")
    abstract fun getAllStored(): LiveData<List<BaseNote>>

    fun getAll(): LiveData<List<BaseNote>> =
        Transformations.map(getAllStored()) { it.opened() }

    @Query("SELECT * FROM BaseNote")
    abstract fun getAllForSyncStored(): List<BaseNote>

    fun getAllForSync(): List<BaseNote> = getAllForSyncStored().opened()

    @Query("SELECT * FROM BaseNote WHERE id = :id")
    abstract fun getStored(id: Long): BaseNote?

    fun get(id: Long): BaseNote? = getStored(id)?.opened()

    @Query("SELECT images FROM BaseNote WHERE id = :id")
    abstract fun getImages(id: Long): String


    @Query("SELECT images FROM BaseNote")
    abstract fun getAllImages(): List<String>

    @Query("SELECT wallpaperName FROM BaseNote WHERE wallpaperName != ''")
    abstract fun getAllWallpapers(): List<String>

    @Query("SELECT audios FROM BaseNote")
    abstract fun getAllAudios(): List<String>


    @Query("SELECT id, reminder FROM BaseNote WHERE reminder IS NOT NULL")
    abstract fun getAllReminders(): List<IdReminder>


    @Query("SELECT id FROM BaseNote WHERE folder = 'DELETED'")
    abstract suspend fun getDeletedNoteIds(): LongArray

    @Query("SELECT id FROM BaseNote WHERE folder = 'DELETED' AND reminder IS NOT NULL")
    abstract suspend fun getDeletedNoteReminderIds(): List<Long>

    @Query("SELECT images FROM BaseNote WHERE folder = 'DELETED'")
    abstract suspend fun getDeletedNoteImages(): List<String>

    @Query("SELECT audios FROM BaseNote WHERE folder = 'DELETED'")
    abstract suspend fun getDeletedNoteAudios(): List<String>


    @Query("UPDATE BaseNote SET folder = :folder WHERE id IN (:ids)")
    abstract suspend fun move(ids: LongArray, folder: Folder)


    @Query("UPDATE BaseNote SET color = :color WHERE id IN (:ids)")
    abstract suspend fun updateColor(ids: LongArray, color: Color)

    @Query("UPDATE BaseNote SET pinned = :pinned WHERE id IN (:ids)")
    abstract suspend fun updatePinned(ids: LongArray, pinned: Boolean)

    @Query("UPDATE BaseNote SET labels = :labels WHERE id = :id")
    abstract suspend fun updateLabels(id: Long, labels: List<String>)

    @Query("UPDATE BaseNote SET items = :items WHERE id = :id")
    abstract suspend fun updateItems(id: Long, items: List<ListItem>)

    @Query("UPDATE BaseNote SET images = :images WHERE id = :id")
    abstract suspend fun updateImages(id: Long, images: List<Image>)

    @Query("UPDATE BaseNote SET audios = :audios WHERE id = :id")
    abstract suspend fun updateAudios(id: Long, audios: List<Audio>)

    @Query("UPDATE BaseNote SET reminder = :reminder WHERE id = :id")
    abstract suspend fun updateReminder(id: Long, reminder: Reminder?)


    /**
     * Both id and position can be invalid.
     *
     * Example of id being invalid - User adds a widget,
     * then goes to Settings and clears app data. Now the
     * widget refers to a list which doesn't exist.
     *
     * Example of position being invalid - User adds a widget,
     * goes to Settings, clears app data and then imports a backup.
     * Even if the backup contains the same list and it is inserted
     * with the same id, it may not be of the safe size.
     *
     * In this case, an exception will be thrown. It is the caller's
     * responsibility to handle it.
     */
    suspend fun updateChecked(id: Long, position: Int, checked: Boolean) {
        val items = requireNotNull(get(id)).items
        items[position].checked = checked
        updateItems(id, items)
    }


    /**
     * Since we store the labels as a JSON Array, it is not possible
     * to perform operations on it. Thus, we use the 'Like' query
     * which can return false positives sometimes.
     *
     * Take for example, a request for all base notes having the label
     * 'Important' The base notes which instead have the label 'Unimportant'
     * will also be returned. To prevent this, we use [Transformations.map] and
     * filter the result accordingly.
     */
    fun getBaseNotesByLabel(label: String): LiveData<List<BaseNote>> {
        val result = getBaseNotesByLabel(label, Folder.NOTES)
        return Transformations.map(result) { list -> list.filter { baseNote -> baseNote.labels.contains(label) } }
    }

    @Query("""SELECT * FROM BaseNote WHERE folder = :folder
        AND (notebookId = 0 OR notebookId NOT IN
            (SELECT id FROM NotebookFolder WHERE passwordHash != ''))
        ORDER BY pinned DESC, timestamp DESC""")
    abstract fun getBaseNotesByLabelStored(folder: Folder): LiveData<List<BaseNote>>

    fun getBaseNotesByLabel(label: String, folder: Folder): LiveData<List<BaseNote>> =
        Transformations.map(getBaseNotesByLabelStored(folder)) { it.opened() }


    suspend fun getListOfBaseNotesByLabel(label: String): List<BaseNote> {
        val result = getListOfBaseNotesByLabelImpl(label)
        return result.filter { baseNote -> baseNote.labels.contains(label) }
    }

    @Query("SELECT * FROM BaseNote")
    abstract suspend fun getListOfBaseNotesByLabelStored(): List<BaseNote>

    suspend fun getListOfBaseNotesByLabelImpl(label: String): List<BaseNote> = getListOfBaseNotesByLabelStored().opened()


    fun getBaseNotesByKeyword(keyword: String, folder: Folder): LiveData<List<BaseNote>> {
        val result = getBaseNotesByKeywordImpl(keyword, folder)
        return Transformations.map(result) { list -> list.filter { baseNote -> matchesKeyword(baseNote, keyword) } }
    }

    @Query("""SELECT * FROM BaseNote WHERE folder = :folder
        AND (notebookId = 0 OR notebookId NOT IN
            (SELECT id FROM NotebookFolder WHERE passwordHash != ''))
        ORDER BY pinned DESC, timestamp DESC""")
    abstract fun getBaseNotesByKeywordStored(folder: Folder): LiveData<List<BaseNote>>

    fun getBaseNotesByKeywordImpl(keyword: String, folder: Folder): LiveData<List<BaseNote>> =
        Transformations.map(getBaseNotesByKeywordStored(folder)) { it.opened() }


    private fun matchesKeyword(baseNote: BaseNote, keyword: String): Boolean {
        if (baseNote.title.contains(keyword, true)) {
            return true
        }
        if (baseNote.body.contains(keyword, true)) {
            return true
        }
        for (label in baseNote.labels) {
            if (label.contains(keyword, true)) {
                return true
            }
        }
        for (item in baseNote.items) {
            if (item.body.contains(keyword, true)) {
                return true
            }
        }
        return false
    }
}