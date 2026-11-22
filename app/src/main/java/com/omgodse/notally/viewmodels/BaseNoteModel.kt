package com.omgodse.notally.viewmodels

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.print.PostPDFGenerator
import android.text.Html
import android.widget.Toast
import androidx.core.text.toHtml
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.omgodse.notally.ActionMode
import com.omgodse.notally.AttachmentDeleteService
import com.omgodse.notally.Cache
import com.omgodse.notally.Progress
import com.omgodse.notally.R
import com.omgodse.notally.ReminderReceiver
import com.omgodse.notally.legacy.Migrations
import com.omgodse.notally.legacy.XMLUtils
import com.omgodse.notally.miscellaneous.Export
import com.omgodse.notally.miscellaneous.IO
import com.omgodse.notally.security.SecureMedia
import com.omgodse.notally.security.RoseInternalLinks
import com.omgodse.notally.security.PinSecurity
import com.omgodse.notally.backup.EncryptedBackups
import com.omgodse.notally.miscellaneous.Operations
import com.omgodse.notally.miscellaneous.applySpans
import com.omgodse.notally.preferences.AutoBackup
import com.omgodse.notally.preferences.BetterLiveData
import com.omgodse.notally.preferences.ListInfo
import com.omgodse.notally.preferences.Preferences
import com.omgodse.notally.preferences.SeekbarInfo
import com.omgodse.notally.room.Attachment
import com.omgodse.notally.room.Audio
import com.omgodse.notally.room.BaseNote
import com.omgodse.notally.room.Color
import com.omgodse.notally.room.Converters
import com.omgodse.notally.room.Folder
import com.omgodse.notally.room.Header
import com.omgodse.notally.room.Image
import com.omgodse.notally.room.Item
import com.omgodse.notally.room.Label
import com.omgodse.notally.room.NotallyDatabase
import com.omgodse.notally.room.Reminder
import com.omgodse.notally.room.Type
import com.omgodse.notally.room.livedata.Content
import com.omgodse.notally.widget.WidgetProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BaseNoteModel(private val app: Application) : AndroidViewModel(app) {

    private val database = NotallyDatabase.getDatabase(app)
    private val labelDao = database.getLabelDao()
    private val commonDao = database.getCommonDao()
    private val baseNoteDao = database.getBaseNoteDao()

    private val labelCache = HashMap<String, Content>()

    var currentFile: File? = null

    val labels = labelDao.getAll()
    val allNotes = baseNoteDao.getAll()
    val selectedNotebookId = MutableLiveData(-1L)
    val notebookFolders = database.getNotebookFolderDao().getAll()
    val baseNotes = Content(
        Transformations.switchMap(selectedNotebookId) { id ->
            baseNoteDao.getFromNotebook(Folder.NOTES, id)
        }, ::transform
    )
    val deletedNotes = Content(baseNoteDao.getFrom(Folder.DELETED), ::transform)
    val archivedNotes = Content(baseNoteDao.getFrom(Folder.ARCHIVED), ::transform)

    val searchQuery = BetterLiveData(Pair(String(), Folder.NOTES))
    val searchResults = Transformations.switchMap(searchQuery) { (keyword, folder) ->
        if (keyword.isEmpty()) {
            MutableLiveData(emptyList())
        } else {
            Transformations.map(baseNoteDao.getBaseNotesByKeyword(keyword, folder), ::transform)
        }
    }

    private val pinned = Header(app.getString(R.string.pinned))
    private val others = Header(app.getString(R.string.others))

    val preferences = Preferences.getInstance(app)

    val mediaRoot = IO.getExternalImagesDirectory(app)
    private val audioRoot = IO.getExternalAudioDirectory(app)

    val importingBackup = MutableLiveData<Progress>()
    val importedBackupSuccessfully = MutableLiveData(false)
    val exportingBackup = MutableLiveData<Progress>()

    val actionMode = ActionMode()

    private val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    init {
        viewModelScope.launch {
            val previousNotes = Migrations.getPreviousNotes(app)
            val previousLabels = Migrations.getPreviousLabels(app)
            if (previousNotes.isNotEmpty() || previousLabels.isNotEmpty()) {
                database.withTransaction {
                    labelDao.insert(previousLabels)
                    baseNoteDao.insert(previousNotes)
                    Migrations.clearAllLabels(app)
                    Migrations.clearAllFolders(app)
                }
            }
        }
        allNotes.observeForever { list ->
            Cache.list = list
        }
    }

    fun getNotesByLabel(label: String): Content {
        if (labelCache[label] == null) {
            labelCache[label] = Content(baseNoteDao.getBaseNotesByLabel(label), ::transform)
        }
        return requireNotNull(labelCache[label])
    }


    fun updateSearchKeyword(keyword: String) {
        val current = searchQuery.value.first
        if (current != keyword) {
            searchQuery.value = Pair(keyword, searchQuery.value.second)
        }
    }

    fun updateSearchFolder(folder: Folder) {
        val current = searchQuery.value.second
        if (current != folder) {
            searchQuery.value = Pair(searchQuery.value.first, folder)
        }
    }


    private fun transform(list: List<BaseNote>) = transform(list, pinned, others)

    suspend fun createNotebook(name: String, passwordHash: String = ""): Long =
        withContext(Dispatchers.IO) {
            database.getNotebookFolderDao().insert(
                com.omgodse.notally.room.NotebookFolder(
                    name = name.trim().take(80),
                    passwordHash = passwordHash
                )
            )
        }

    /**
     * Conserva notas y adjuntos: solo quita su pertenencia a la carpeta.
     * Ambas escrituras deben ser atómicas para evitar referencias huérfanas.
     */
    suspend fun deleteNotebookKeepingNotes(id: Long): Boolean =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val folders = database.getNotebookFolderDao()
                if (folders.get(id) == null) return@withTransaction false
                folders.unfile(id)
                folders.delete(id)
                true
            }
        }

    fun setActiveNotebook(id: Long) {
        selectedNotebookId.value = id
    }

    suspend fun moveNoteToNotebook(id: Long, notebookId: Long) =
        withContext(Dispatchers.IO) { baseNoteDao.moveToNotebook(id, notebookId) }

    suspend fun getNotebookFolders() = withContext(Dispatchers.IO) {
        database.getNotebookFolderDao().listOnce()
    }


    fun savePreference(info: SeekbarInfo, value: Int) = executeAsync { preferences.savePreference(info, value) }

    fun savePreference(info: ListInfo, value: String) = executeAsync { preferences.savePreference(info, value) }


    fun disableAutoBackup() {
        releasePreviousBackupFolder()
        executeAsync { preferences.savePreference(AutoBackup, AutoBackup.emptyPath) }
    }

    fun setAutoBackupPath(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Primero aseguramos el nuevo destino; no revocamos el anterior en caso de error.
        app.contentResolver.takePersistableUriPermission(uri, flags)
        releasePreviousBackupFolder(except = uri)
        executeAsync { preferences.savePreference(AutoBackup, uri.toString()) }
    }

    private fun releasePreviousBackupFolder(except: Uri? = null) {
        val old = preferences.autoBackup.value
        if (old == AutoBackup.emptyPath) return
        val priorUri = Uri.parse(old)
        if (priorUri == except) return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            app.contentResolver.releasePersistableUriPermission(priorUri, flags)
        } catch (_: SecurityException) {
            // El proveedor pudo revocar el permiso externamente.
        }
    }


    // La exportación local histórica se eliminó. Los respaldos nuevos
    // se cifran y se suben únicamente desde RoseDriveWorker.

    fun importBackup(uri: Uri) {
        when (app.contentResolver.getType(uri)) {
            "text/xml" -> importXmlBackup(uri)
            "application/zip" -> importZipBackup(uri)
        }
    }


    /**
     * We use a ZipFile instead of ZipInputStream because importing one image of 3 MB takes 1 second
     * on a phone with 6GB RAM. This is non negligible so we need to display the progress. However, with a stream
     * there is no way to know how many images are there, hence we can only display an indeterminate progress bar
     * which is almost as useless as displaying no progress bar.
     *
     * We only import the images referenced in notes. e.g If someone has added garbage to the
     * ZIP file, like a 100 MB image, ignore it.
     */
    private fun importZipBackup(uri: Uri) {
        importedBackupSuccessfully.value = false
        val backupDir = getBackupDir()

        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            importedBackupSuccessfully.postValue(false)
            finishImporting(backupDir)
            Toast.makeText(app, R.string.invalid_backup, Toast.LENGTH_LONG).show()
            Operations.log(app, throwable)
        }

        viewModelScope.launch(exceptionHandler) {
            importingBackup.value = Progress(true, 0, 0, true)

            withContext(Dispatchers.IO) {
                val stream = requireNotNull(app.contentResolver.openInputStream(uri))
                val destination = File(backupDir, "TEMP.zip")
                IO.copyStreamToFile(stream, destination)

                val zipFile = ZipFile(destination)
                val databaseEntry = zipFile.getEntry(NotallyDatabase.DatabaseName)

                val databaseFile = File(backupDir, NotallyDatabase.DatabaseName)
                val inputStream = zipFile.getInputStream(databaseEntry)
                IO.copyStreamToFile(inputStream, databaseFile)

                val database = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)

                val labelCursor = database.query("Label", null, null, null, null, null, null)
                val baseNoteCursor = database.query("BaseNote", null, null, null, null, null, null)

                val labels = convertCursorToList(labelCursor) { cursor -> convertCursorToLabel(cursor) }
                val previousIds = ArrayList<Long>()
                val baseNotes = convertCursorToList(baseNoteCursor) { cursor ->
                    previousIds.add(cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                    convertCursorToBaseNote(cursor)
                }
                val pinHashes = EncryptedBackups.readNotePins(zipFile)

                // Preservar relaciones de las carpetas aunque haya IDs ocupados
                // por carpetas existentes en este dispositivo.
                val notebooks = ArrayList<com.omgodse.notally.room.NotebookFolder>()
                val available = database.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='NotebookFolder'",
                    null).use { it.moveToFirst() }
                if (available) {
                    database.query("NotebookFolder", null, null, null, null, null, null).use { rows ->
                        while (rows.moveToNext()) {
                            notebooks.add(com.omgodse.notally.room.NotebookFolder(
                                id = rows.getLong(rows.getColumnIndexOrThrow("id")),
                                name = rows.getString(rows.getColumnIndexOrThrow("name")),
                                passwordHash = rows.getString(rows.getColumnIndexOrThrow("passwordHash")),
                                createdAt = rows.getLong(rows.getColumnIndexOrThrow("createdAt")),
                                wallpaperName = rows.getColumnIndex("wallpaperName")
                                    .takeIf { it >= 0 }?.let(rows::getString) ?: ""
                            ))
                        }
                    }
                }
                // Restaurar una sola vez los fondos compartidos. Las carpetas
                // y las notas se enlazan a nuevos nombres aleatorios locales.
                val wallpaperNames = (notebooks.map { it.wallpaperName } +
                    baseNotes.map { it.wallpaperName }).filter { it.isNotBlank() }.distinct()
                val wallpaperRemap = HashMap<String, String>()
                val wallpaperRoot = File(app.filesDir, "SecureMedia/Wallpapers")
                    .apply { mkdirs() }
                for (oldName in wallpaperNames) {
                    val entry = zipFile.getEntry("Wallpapers/$oldName")
                        ?: throw java.io.IOException("Missing folder or note wallpaper in encrypted backup")
                    val newName = "${UUID.randomUUID()}.wall"
                    SecureMedia.encryptFromStream(app, zipFile.getInputStream(entry),
                        File(wallpaperRoot, newName))
                    wallpaperRemap[oldName] = newName
                }

                val remap = HashMap<Long, Long>()
                for (folder in notebooks) {
                    val newId = this@BaseNoteModel.database.getNotebookFolderDao()
                        .insert(folder.copy(id = 0L,
                            wallpaperName = wallpaperRemap[folder.wallpaperName] ?: ""))
                    remap[folder.id] = newId
                }
                val resolvedNotes = baseNotes.map { note ->
                    note.copy(notebookId = remap[note.notebookId] ?: 0L)
                }

                delay(1000)

                val total = wallpaperNames.size + baseNotes.fold(0) { acc, baseNote ->
                    acc + baseNote.images.size + baseNote.audios.size
                }
                var current = wallpaperNames.size + 1

                // La importación cambia los nombres cifrados: los spans incrustados
                // deben actualizarse con el nuevo nombre, sin perder su posición.
                val imageNameMap = HashMap<String, String>()
                // Don't let a single image bring down the entire backup
                baseNotes.forEach { baseNote ->
                    baseNote.images.forEach { image ->
                        try {
                            val entry = zipFile.getEntry("Images/${image.name}")
                            if (entry != null) {
                                val extension = image.name.substringAfterLast(".")
                                val name = "${UUID.randomUUID()}.$extension"
                                val file = File(mediaRoot, name)
                                image.name = name
                                imageNameMap[entry.name.substringAfterLast('/')] = name
                                val imageStream = zipFile.getInputStream(entry)
                                SecureMedia.encryptFromStream(app, imageStream, file)
                            }
                        } catch (exception: Exception) {
                            Operations.log(app, exception)
                        } finally {
                            importingBackup.postValue(Progress(true, current, total, false))
                            current++
                        }
                    }
                    baseNote.audios.forEach { audio ->
                        try {
                            val entry = zipFile.getEntry("Audios/${audio.name}")
                            if (entry != null) {
                                val name = "${UUID.randomUUID()}.m4a"
                                val file = File(audioRoot, name)
                                audio.name = name
                                val audioStream = zipFile.getInputStream(entry)
                                SecureMedia.encryptFromStream(app, audioStream, file)
                            }
                        } catch (exception: Exception) {
                            Operations.log(app, exception)
                        } finally {
                            importingBackup.postValue(Progress(true, current, total, false))
                            current++
                        }
                    }
                }

                val importedIds = commonDao.importBackup(resolvedNotes.map { note ->
                    note.copy(wallpaperName = wallpaperRemap[note.wallpaperName] ?: "")
                }, labels)
                val noteIdMap = previousIds.zip(importedIds).toMap()
                PinSecurity.restoreHashes(app, noteIdMap, pinHashes)
                // El destino de cada vínculo es un ID, no un título. Al
                // restaurar, las IDs cambian y deben reescribirse los spans.
                for ((index, note) in resolvedNotes.withIndex()) {
                    val localId = importedIds.getOrNull(index) ?: continue
                    val converted = note.spans.map { span ->
                        when {
                            span.link && RoseInternalLinks.parse(span.url) != null ->
                                span.copy(url = RoseInternalLinks.remap(span.url, noteIdMap, remap))
                            span.imageName != null && imageNameMap.containsKey(span.imageName) ->
                                span.copy(imageName = imageNameMap[span.imageName])
                            else -> span
                        }
                    }
                    if (converted != note.spans) {
                        baseNoteDao.insert(note.copy(id = localId,
                            wallpaperName = wallpaperRemap[note.wallpaperName] ?: "",
                            spans = converted))
                    }
                }
                val reminders = baseNoteDao.getAllReminders()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (manager.canScheduleExactAlarms()) {
                        ReminderReceiver.rescheduleReminders(app, manager, reminders)
                    }
                } else ReminderReceiver.rescheduleReminders(app, manager, reminders)
            }

            importedBackupSuccessfully.value = true
            finishImporting(backupDir)
        }
    }

    private fun convertCursorToLabel(cursor: Cursor): Label {
        val value = cursor.getString(cursor.getColumnIndexOrThrow("value"))
        return Label(value)
    }

    private fun convertCursorToBaseNote(cursor: Cursor): BaseNote {
        val typeTmp = cursor.getString(cursor.getColumnIndexOrThrow("type"))
        val folderTmp = cursor.getString(cursor.getColumnIndexOrThrow("folder"))
        val colorTmp = cursor.getString(cursor.getColumnIndexOrThrow("color"))
        val title = cursor.getString(cursor.getColumnIndexOrThrow("title"))
        val pinnedTmp = cursor.getInt(cursor.getColumnIndexOrThrow("pinned"))
        val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"))
        val labelsTmp = cursor.getString(cursor.getColumnIndexOrThrow("labels"))
        val body = cursor.getString(cursor.getColumnIndexOrThrow("body"))
        val spansTmp = cursor.getString(cursor.getColumnIndexOrThrow("spans"))
        val itemsTmp = cursor.getString(cursor.getColumnIndexOrThrow("items"))

        val pinned = when (pinnedTmp) {
            0 -> false
            1 -> true
            else -> throw IllegalArgumentException("pinned must be 0 or 1")
        }

        val type = Type.valueOf(typeTmp)
        val folder = Folder.valueOf(folderTmp)
        val color = Color.valueOf(colorTmp)

        val labels = Converters.jsonToLabels(labelsTmp)
        val spans = Converters.jsonToSpans(spansTmp)
        val items = Converters.jsonToItems(itemsTmp)

        val imagesIndex = cursor.getColumnIndex("images")
        val images = if (imagesIndex != -1) {
            Converters.jsonToImages(cursor.getString(imagesIndex))
        } else emptyList()

        val audiosIndex = cursor.getColumnIndex("audios")
        val audios = if (audiosIndex != -1) {
            Converters.jsonToAudios(cursor.getString(audiosIndex))
        } else emptyList()

        var reminder: Reminder? = null
        val reminderIndex = cursor.getColumnIndex("reminder")
        if (reminderIndex != -1) {
            if (!cursor.isNull(reminderIndex)) {
                reminder = Converters.jsonToReminder(cursor.getString(reminderIndex))
            }
        }

        return BaseNote(0, type, folder, color, title, pinned, timestamp, labels, body, spans, items, images, audios, reminder,
            cursor.getColumnIndex("notebookId").takeIf { it >= 0 }?.let(cursor::getLong) ?: 0L,
            cursor.getColumnIndex("wallpaperName").takeIf { it >= 0 }?.let(cursor::getString) ?: "")
    }

    private fun <T> convertCursorToList(cursor: Cursor, convert: (cursor: Cursor) -> T): ArrayList<T> {
        val list = ArrayList<T>(cursor.count)
        while (cursor.moveToNext()) {
            val item = convert(cursor)
            list.add(item)
        }
        cursor.close()
        return list
    }

    private fun finishImporting(backupDir: File) {
        importingBackup.value = Progress(false, 0, 0, false)
        clear(backupDir)
    }


    private fun importXmlBackup(uri: Uri) {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Operations.log(app, throwable)
            Toast.makeText(app, R.string.invalid_backup, Toast.LENGTH_LONG).show()
        }

        viewModelScope.launch(exceptionHandler) {
            withContext(Dispatchers.IO) {
                val stream = requireNotNull(app.contentResolver.openInputStream(uri))
                val backup = XMLUtils.readBackupFromStream(stream)
                commonDao.importBackup(backup.first, backup.second)
            }
            Toast.makeText(app, R.string.imported_backup, Toast.LENGTH_LONG).show()
        }
    }


    fun writeCurrentFileToUri(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val output = app.contentResolver.openOutputStream(uri) as FileOutputStream
                output.channel.truncate(0)
                val input = FileInputStream(requireNotNull(currentFile))
                input.copyTo(output)
                input.close()
                output.close()
            }
            Toast.makeText(app, R.string.saved_to_device, Toast.LENGTH_LONG).show()
        }
    }


    suspend fun getJSONFile(baseNote: BaseNote) = withContext(Dispatchers.IO) {
        val file = File(getExportedPath(), "Untitled.json")
        val json = getJSON(baseNote)
        file.writeText(json)
        file
    }

    suspend fun getTXTFile(baseNote: BaseNote) = withContext(Dispatchers.IO) {
        val file = File(getExportedPath(), "Untitled.txt")
        val writer = file.bufferedWriter()

        val date = DateFormat.getDateInstance(DateFormat.FULL).format(baseNote.timestamp)

        val body = when (baseNote.type) {
            Type.NOTE -> baseNote.body
            Type.LIST -> Operations.getBody(baseNote.items)
        }

        if (baseNote.title.isNotEmpty()) {
            writer.append("${baseNote.title}\n\n")
        }
        if (preferences.showDateCreated()) {
            writer.append("$date\n\n")
        }
        writer.append(body)
        writer.close()

        file
    }

    suspend fun getHTMLFile(baseNote: BaseNote) = withContext(Dispatchers.IO) {
        val file = File(getExportedPath(), "Untitled.html")
        val html = getHTML(baseNote, preferences.showDateCreated())
        file.writeText(html)
        file
    }

    fun getPDFFile(baseNote: BaseNote, onResult: PostPDFGenerator.OnResult) {
        val file = File(getExportedPath(), "Untitled.pdf")
        val html = getHTML(baseNote, preferences.showDateCreated())
        PostPDFGenerator.create(file, html, app, onResult)
    }


    fun pinBaseNote(pinned: Boolean) {
        val id = actionMode.selectedIds.toLongArray()
        actionMode.close(false)
        executeAsync { baseNoteDao.updatePinned(id, pinned) }
    }

    fun colorBaseNote(color: Color) {
        val ids = actionMode.selectedIds.toLongArray()
        actionMode.close(true)
        executeAsync { baseNoteDao.updateColor(ids, color) }
    }

    fun copyBaseNote() {
        val ids = actionMode.selectedIds.toLongArray()
        actionMode.close(true)
        executeAsync { baseNoteDao.copy(ids) }
    }

    fun moveBaseNotes(folder: Folder) {
        val ids = actionMode.selectedIds.toLongArray()
        actionMode.close(false)
        executeAsync { baseNoteDao.move(ids, folder) }
    }

    fun updateBaseNoteLabels(labels: List<String>, id: Long) {
        actionMode.close(true)
        executeAsync { baseNoteDao.updateLabels(id, labels) }
    }


    fun deleteBaseNotes() {
        val ids = LongArray(actionMode.selectedNotes.size)
        val reminderIds = ArrayList<Long>()
        val attachments = ArrayList<Attachment>()
        actionMode.selectedNotes.onEachIndexed { index, entry ->
            val id = entry.key
            val baseNote = entry.value
            ids[index] = id
            if (baseNote.reminder != null) {
                reminderIds.add(id)
            }
            attachments.addAll(baseNote.images)
            attachments.addAll(baseNote.audios)
        }
        actionMode.close(false)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { baseNoteDao.delete(ids) }
            informOtherComponents(attachments, ids, reminderIds)
        }
    }

    fun deleteAllBaseNotes() {
        viewModelScope.launch {
            val ids: LongArray
            val reminderIds: List<Long>
            val images = ArrayList<Image>()
            val audios = ArrayList<Audio>()
            withContext(Dispatchers.IO) {
                ids = baseNoteDao.getDeletedNoteIds()
                reminderIds = baseNoteDao.getDeletedNoteReminderIds()
                val imageStrings = baseNoteDao.getDeletedNoteImages()
                val audioStrings = baseNoteDao.getDeletedNoteAudios()
                imageStrings.flatMapTo(images) { json -> Converters.jsonToImages(json) }
                audioStrings.flatMapTo(audios) { json -> Converters.jsonToAudios(json) }
                baseNoteDao.deleteFrom(Folder.DELETED)
            }
            val attachments = ArrayList<Attachment>(images.size + audios.size)
            attachments.addAll(images)
            attachments.addAll(audios)
            informOtherComponents(attachments, ids, reminderIds)
        }
    }

    private fun informOtherComponents(attachments: ArrayList<Attachment>, ids: LongArray, reminderIds: List<Long>) {
        if (attachments.isNotEmpty()) {
            AttachmentDeleteService.start(app, attachments)
        }
        if (ids.isNotEmpty()) {
            WidgetProvider.sendBroadcast(app, ids)
        }
        if (reminderIds.isNotEmpty()) {
            ReminderReceiver.deleteReminders(app, manager, reminderIds)
        }
    }


    suspend fun getAllLabels() = withContext(Dispatchers.IO) { labelDao.getArrayOfAll() }

    fun deleteLabel(value: String) = executeAsync { commonDao.deleteLabel(value) }

    fun insertLabel(label: Label, onComplete: (success: Boolean) -> Unit) =
        executeAsyncWithCallback({ labelDao.insert(label) }, onComplete)

    fun updateLabel(oldValue: String, newValue: String, onComplete: (success: Boolean) -> Unit) =
        executeAsyncWithCallback({ commonDao.updateLabel(oldValue, newValue) }, onComplete)


    private fun getEmptyFolder(name: String): File {
        val folder = File(app.cacheDir, name)
        if (folder.exists()) {
            clear(folder)
        } else folder.mkdir()
        return folder
    }

    private fun clear(directory: File) {
        val files = directory.listFiles()
        if (files != null) {
            for (file in files) {
                file.delete()
            }
        }
    }

    private fun getBackupDir() = getEmptyFolder("backup")

    private fun getExportedPath() = getEmptyFolder("exported")


    private fun getJSON(baseNote: BaseNote): String {
        val jsonObject = JSONObject()
            .put("type", baseNote.type.name)
            .put("color", baseNote.color.name)
            .put("title", baseNote.title)
            .put("pinned", baseNote.pinned)
            .put("date-created", baseNote.timestamp)
            .put("labels", JSONArray(baseNote.labels))

        when (baseNote.type) {
            Type.NOTE -> {
                jsonObject.put("body", baseNote.body)
                jsonObject.put("spans", Converters.spansToJSONArray(baseNote.spans))
            }

            Type.LIST -> {
                jsonObject.put("items", Converters.itemsToJSONArray(baseNote.items))
            }
        }

        return jsonObject.toString(2)
    }

    private fun getHTML(baseNote: BaseNote, showDateCreated: Boolean) = buildString {
        val date = DateFormat.getDateInstance(DateFormat.FULL).format(baseNote.timestamp)
        val title = Html.escapeHtml(baseNote.title)

        append("<!DOCTYPE html>")
        append("<html><head>")
        append("<meta charset=\"UTF-8\"><title>$title</title>")
        append("<style>h1{letter-spacing:0.5px}body{letter-spacing:0.25px;line-height:1.3}</style>")
        append("</head><body>")
        append("<h2>$title</h2>")

        if (showDateCreated) {
            append("<p>$date</p>")
        }

        when (baseNote.type) {
            Type.NOTE -> {
                val body = baseNote.body.applySpans(baseNote.spans).toHtml()
                append(body)
            }

            Type.LIST -> {
                append("<ol style=\"list-style: none; padding: 0;\">")
                baseNote.items.forEach { item ->
                    val body = Html.escapeHtml(item.body)
                    val checked = if (item.checked) "checked" else ""
                    append("<li><input style=\"margin-right:8px;\" type=\"checkbox\" $checked>$body</li>")
                }
                append("</ol>")
            }
        }
        append("</body></html>")
    }


    private fun executeAsync(function: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { function() }
    }

    companion object {

        fun transform(list: List<BaseNote>, pinned: Header, others: Header): List<Item> {
            if (list.isEmpty()) {
                return list
            } else {
                val firstNote = list[0]
                return if (firstNote.pinned) {
                    val newList = ArrayList<Item>(list.size + 2)
                    newList.add(pinned)

                    val firstUnpinnedNote = list.indexOfFirst { baseNote -> !baseNote.pinned }
                    list.forEachIndexed { index, baseNote ->
                        if (index == firstUnpinnedNote) {
                            newList.add(others)
                        }
                        newList.add(baseNote)
                    }
                    newList
                } else list
            }
        }
    }
}