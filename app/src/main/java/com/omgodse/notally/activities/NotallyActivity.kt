package com.omgodse.notally.activities

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import com.omgodse.notally.security.PinSecurity
import com.omgodse.notally.security.PinDialogs
import com.omgodse.notally.security.BiometricSecurity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import com.omgodse.notally.security.SecureMedia
import java.io.File
import java.util.UUID
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Transition
import androidx.transition.TransitionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialFade
import com.omgodse.notally.R
import com.omgodse.notally.backup.RoseDriveState
import com.omgodse.notally.backup.BackupScheduler
import com.omgodse.notally.room.NotallyDatabase
import com.omgodse.notally.security.NotebookPassword
import com.omgodse.notally.security.NoteWallpaperStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.text.InputType
import android.widget.EditText
import android.widget.ImageButton
import com.omgodse.notally.databinding.ActivityNotallyBinding
import com.omgodse.notally.databinding.DialogProgressBinding
import com.omgodse.notally.databinding.DialogReminderBinding
import com.omgodse.notally.databinding.DialogColorBinding
import com.omgodse.notally.recyclerview.adapter.ColorAdapter
import com.omgodse.notally.recyclerview.ItemListener
import com.omgodse.notally.room.Color as NoteColor
import com.omgodse.notally.image.ImageError
import com.omgodse.notally.miscellaneous.Constants
import com.omgodse.notally.miscellaneous.Operations
import com.omgodse.notally.miscellaneous.add
import com.omgodse.notally.preferences.TextSize
import com.omgodse.notally.recyclerview.adapter.AudioAdapter
import com.omgodse.notally.recyclerview.adapter.ErrorAdapter
import com.omgodse.notally.recyclerview.adapter.PreviewImageAdapter
import com.omgodse.notally.room.Audio
import com.omgodse.notally.room.Folder
import com.omgodse.notally.room.Frequency
import com.omgodse.notally.room.Image
import com.omgodse.notally.room.Reminder
import com.omgodse.notally.room.Type
import com.omgodse.notally.viewmodels.NotallyModel
import com.omgodse.notally.widget.WidgetProvider
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar

abstract class NotallyActivity(private val type: Type) : NotasBaseActivity() {

    internal lateinit var binding: ActivityNotallyBinding
    internal val model: NotallyModel by viewModels()
    private var permittedToSave = false
    private var noteLoadStarted = false
    private var pendingState: Bundle? = null
    private var syncActionButton: ImageButton? = null
    private var editorDirty = false
    private var metadataExpanded = false
    private var smartColorJob: kotlinx.coroutines.Job? = null

    protected fun markNoteEdited() {
        if (model.type == Type.LIST) updateCharacterCount()
        editorDirty = true
        updateNoteSyncIndicator()
    }

    private fun setupCollapsibleMetadata() {
        // Siempre cerrado al entrar. No cambia la fecha ni el contenido.
        metadataExpanded = false
        binding.StatsRow.visibility = View.GONE
        binding.StatsChevron.rotation = 0f
        updateMetadataAccessibility()
        binding.MetadataToggle.setOnClickListener {
            metadataExpanded = !metadataExpanded
            android.transition.TransitionManager.beginDelayedTransition(
                binding.InfoPills,
                android.transition.AutoTransition().apply { duration = 180L }
            )
            binding.StatsRow.visibility = if (metadataExpanded) View.VISIBLE else View.GONE
            binding.StatsChevron.animate()
                .rotation(if (metadataExpanded) 180f else 0f)
                .setDuration(180L)
                .start()
            updateMetadataAccessibility()
        }
    }

    private fun updateMetadataAccessibility() {
        val action = if (metadataExpanded) R.string.notas_hide_statistics
            else R.string.notas_show_statistics
        binding.MetadataToggle.contentDescription =
            "${binding.DateCreated.text}. ${getString(action)}"
    }

    override fun onBackPressed() {
        if (model.searchEnabled.value) {
            model.closeSearch()
        } else super.onBackPressed()
    }


    override fun finish() {
        if (!permittedToSave) {
            super.finish()
            return
        }
        lifecycleScope.launch {
            try {
                val savedId = model.saveNote()
                if (savedId > 0L) WidgetProvider.sendBroadcast(application, savedId)
                super.finish()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                android.util.Log.e("NotasLinkRecovery", "Failed to save note while exiting", error)
                Toast.makeText(this@NotallyActivity, R.string.something_went_wrong,
                    Toast.LENGTH_LONG).show()
                // No cerrar sin un guardado correcto, ni eliminar el borrador.
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("id", if (permittedToSave) model.id
            else intent.getLongExtra(Constants.SelectedBaseNote, 0L))
        if (permittedToSave) {
            lifecycleScope.launch {
                try {
                    val savedId = model.saveNote()
                    if (savedId > 0L) WidgetProvider.sendBroadcast(application, savedId)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    android.util.Log.e("NotasLinkRecovery", "Failed to save note during state change", error)
                }
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.type = type
        model.marker.color = ContextCompat.getColor(this, R.color.highlight)
        initialiseBinding()
        setContentView(binding.root)
        pendingState = savedInstanceState
        binding.ScrollView.visibility = View.GONE
    }

    override fun onAppAccessGranted() {
        if (noteLoadStarted) return
        noteLoadStarted = true
        val id = pendingState?.getLong("id")
            ?: intent.getLongExtra(Constants.SelectedBaseNote, 0L)
        if (id == 0L) model.notebookId =
            intent.getLongExtra(Constants.SelectedNotebookId, 0L)
        if (id > 0L) {
            requestFolderAccess(id) {
                if (PinSecurity.noteLocked(this, id)) {
                    PinDialogs.check(this, PinSecurity.noteKey(id), onVerified = {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                        loadAuthorizedNote(id)
                    }, onCancel = { finish() })
                } else loadAuthorizedNote(id)
            }
        } else loadAuthorizedNote(id)
    }

    private fun requestFolderAccess(noteId: Long, onGranted: () -> Unit) {
        val note = NotallyDatabase.getDatabase(application).getBaseNoteDao().get(noteId)
        val folder = note?.notebookId?.takeIf { it > 0L }?.let {
            NotallyDatabase.getDatabase(application).getNotebookFolderDao().getNow(it)
        }
        if (folder == null || folder.passwordHash.isBlank()) {
            onGranted()
            return
        }
        val key = "folder:${folder.id}"
        if (BiometricSecurity.isEnabled(this, key)) {
            BiometricSecurity.authenticate(this, folder.name, onAuthenticated = {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                onGranted()
            }, onFallback = { requestFolderPassword(folder, onGranted) })
        } else requestFolderPassword(folder, onGranted)
    }

    private fun requestFolderPassword(
        folder: com.omgodse.notally.room.NotebookFolder, onGranted: () -> Unit
    ) {
        val entry = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.notebook_password)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(folder.name)
            .setView(entry)
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.continue_, null).create()
        dialog.setOnCancelListener { finish() }
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener {
                    val secret = entry.text.toString().toCharArray()
                    lifecycleScope.launch {
                        val accepted = withContext(Dispatchers.Default) {
                            try { NotebookPassword.verify(secret, folder.passwordHash) }
                            finally { secret.fill('\u0000') }
                        }
                        if (accepted) {
                            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                            dialog.dismiss()
                            onGranted()
                        } else entry.error = getString(R.string.notebook_wrong_password)
                    }
                }
        }
        dialog.show()
    }

    private fun loadAuthorizedNote(id: Long) {
        lifecycleScope.launch {
            try {
                if (model.isFirstInstance) {
                    model.setState(id)
                    if (model.isNewNote && intent.action == Intent.ACTION_SEND) {
                        handleSharedNote()
                    }
                    model.isFirstInstance = false
                }
                permittedToSave = true
                setupListeners()
                setStateFromModel()
                configureUI()
                editorDirty = false
                updateNoteSyncIndicator()
                binding.ScrollView.visibility = View.VISIBLE
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                // Rescate no destructivo de una nota cuyo formato no se pueda abrir.
                // El registro y sus adjuntos siguen íntegros en Room.
                permittedToSave = false
                android.util.Log.e("NotasLinkRecovery", "Unable to open note ID $id", error)
                Toast.makeText(this@NotallyActivity, R.string.something_went_wrong,
                    Toast.LENGTH_LONG).show()
                startActivity(Intent(this@NotallyActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                REQUEST_NOTE_WALLPAPER -> {
                    data?.data?.let(::saveNewWallpaper)
                }
                REQUEST_ADD_IMAGES -> {
                    val uri = data?.data
                    val clipData = data?.clipData
                    if (uri != null) {
                        val uris = arrayOf(uri)
                        model.addImages(uris)
                    } else if (clipData != null) {
                        val uris = Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
                        model.addImages(uris)
                    }
                }
                REQUEST_VIEW_IMAGES -> {
                    val list = data?.getParcelableArrayListExtra<Image>(ViewImage.DELETED_IMAGES)
                    if (!list.isNullOrEmpty()) {
                        model.deleteImages(list)
                    }
                }
                REQUEST_SELECT_LABELS -> {
                    val list = data?.getStringArrayListExtra(SelectLabels.SELECTED_LABELS)
                    if (list != null && list != model.labels.value) {
                        model.labels.value = list
                    }
                }
                REQUEST_RECORD_AUDIO -> model.addAudio()
                REQUEST_PLAY_AUDIO -> {
                    val audio = data?.getParcelableExtra<Audio>(PlayAudio.AUDIO)
                    if (audio != null) {
                        model.deleteAudio(audio)
                    }
                }
            }
        }
        // Bug in Samsung: Even if permission was granted result code is RESULT_CANCELED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (requestCode == REQUEST_ALARM_PERMISSION) {
                val manager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                if (manager.canScheduleExactAlarms()) {
                    displayReminderDialog()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_NOTIFICATION_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    checkAlarmPermission()
                }
            }
            REQUEST_AUDIO_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    recordAudio()
                } else handleRejection()
            }
        }
    }


    abstract fun configureUI()

    open fun setupListeners() {
        binding.EnterTitle.doAfterTextChanged { text ->
            model.title = requireNotNull(text).trim().toString()
            markNoteEdited()
        }
        if (model.type == Type.NOTE) {
            binding.EnterBody.doAfterTextChanged { editable ->
                // Always keep the model synchronized with the actual editor.
                // Link-only changes are also bound explicitly in TakeNote.
                if (editable != null) model.body = editable
                updateCharacterCount()
                markNoteEdited()
            }
        }
    }

    private fun updateCharacterCount() {
        if (model.type == Type.LIST) {
            val total = model.items.count { it.body.isNotBlank() }
            val completed = model.items.count { it.body.isNotBlank() && it.checked }
            binding.WordCount.text = resources.getQuantityString(
                R.plurals.notas_tasks_total, total, total)
            binding.CharacterCount.text = resources.getQuantityString(
                R.plurals.notas_tasks_completed, completed, completed)
            return
        }
        val body = model.body.toString().replace("\uFFFC", "")
        val characters = body.codePointCount(0, body.length)
        val words = Regex("""[\p{L}\p{N}]+(?:['’_-][\p{L}\p{N}]+)*""")
            .findAll(body).count()
        binding.WordCount.text = resources.getQuantityString(
            R.plurals.notas_word_count, words, words)
        binding.CharacterCount.text = resources.getQuantityString(
            R.plurals.notas_character_count, characters, characters)
    }

    open fun setStateFromModel() {
        // Guardamos timestamp original: no usar la hora del dispositivo
        // en cada apertura ni reemplazarlo por la última modificación.
        val dateFormatter = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val timeFormatter = android.text.format.DateFormat.getTimeFormat(this)
        val createdAt = java.util.Date(model.timestamp)
        binding.DateCreated.text =
            "${dateFormatter.format(createdAt)} · ${timeFormatter.format(createdAt)}"
        updateMetadataAccessibility()

        binding.EnterTitle.setText(model.title)
        updateCharacterCount()

        binding.EnterBody.setEditableFactory(object : Editable.Factory() {

            override fun newEditable(source: CharSequence?): Editable {
                return model.body
            }
        })
        val end = model.body.getSpanEnd(model.marker)
        if (end != -1) {
            binding.EnterBody.requestFocus()
            binding.EnterBody.setSelection(end)
        }
        // La carpeta se conoce después de cargar la nota; refrescar entonces
        // el wallpaper heredado y la paleta (no solo al cambiar el color).
        applyNoteAppearance(model.color.value)
    }


    private fun handleSharedNote() {
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)

        val string = intent.getStringExtra(Intent.EXTRA_TEXT)
        val charSequence = intent.getCharSequenceExtra(Operations.extraCharSequence)
        val body = charSequence ?: string

        if (body != null) {
            model.body = Editable.Factory.getInstance().newEditable(body)
        }
        if (title != null) {
            model.title = title
        }
    }


    @RequiresApi(24)
    private fun checkAudioPermission() {
        val permission = Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            if (shouldShowRequestPermissionRationale(permission)) {
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.please_grant_notally_audio)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.continue_) { _, _ ->
                        requestPermissions(arrayOf(permission), REQUEST_AUDIO_PERMISSION)
                    }
                    .show()
            } else requestPermissions(arrayOf(permission), REQUEST_AUDIO_PERMISSION)
        } else recordAudio()
    }

    private fun checkAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (manager.canScheduleExactAlarms()) {
                displayReminderDialog()
            } else {
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.please_grant_notally_alarm)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.continue_) { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        intent.data = Uri.parse("package:$packageName")
                        startActivityForResult(intent, REQUEST_ALARM_PERMISSION)
                    }
                    .show()
            }
        } else displayReminderDialog()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                if (shouldShowRequestPermissionRationale(permission)) {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(R.string.please_grant_notally_notification)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.continue_) { _, _ ->
                            requestPermissions(arrayOf(permission), REQUEST_NOTIFICATION_PERMISSION)
                        }
                        .show()
                } else requestPermissions(arrayOf(permission), REQUEST_NOTIFICATION_PERMISSION)
            } else checkAlarmPermission()
        } else checkAlarmPermission()
    }


    private fun recordAudio() {
        if (model.audioRoot != null) {
            val intent = Intent(this, RecordAudio::class.java)
            startActivityForResult(intent, REQUEST_RECORD_AUDIO)
        } else Toast.makeText(this, R.string.insert_an_sd_card_audio, Toast.LENGTH_LONG).show()
    }

    private fun handleRejection() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.to_record_audio)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.settings) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:${packageName}")
                startActivity(intent)
            }
            .show()
    }

    private fun selectImages() {
        if (model.imageRoot != null) {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(intent, REQUEST_ADD_IMAGES)
        } else Toast.makeText(this, R.string.insert_an_sd_card_images, Toast.LENGTH_LONG).show()
    }


    private fun share() {
        val body = when (type) {
            Type.NOTE -> model.body
            Type.LIST -> Operations.getBody(model.items)
        }
        Operations.shareNote(this, model.title, body)
    }

    private fun label() {
        val intent = Intent(this, SelectLabels::class.java)
        intent.putStringArrayListExtra(SelectLabels.SELECTED_LABELS, model.labels.value)
        startActivityForResult(intent, REQUEST_SELECT_LABELS)
    }

    private fun delete() {
        model.folder = Folder.DELETED
        finish()
    }

    private fun restore() {
        model.folder = Folder.NOTES
        finish()
    }

    private fun archive() {
        model.folder = Folder.ARCHIVED
        finish()
    }

    private fun deleteForever() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.delete_note_forever)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    model.deleteBaseNote()
                    super.finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }


    private fun saveNewWallpaper(uri: Uri) {
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    NoteWallpaperStore.import(application, uri)
                }
                model.wallpaperName = saved
                markNoteEdited()
                applyNoteAppearance(model.color.value)
            } catch (error: Exception) {
                android.util.Log.e("RoseWallpaper", "Note wallpaper import failed", error)
                Toast.makeText(this@NotallyActivity,
                    R.string.wallpaper_invalid, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyNoteAppearance(noteColor: NoteColor, smartOverride: Int? = null) {
        // Modo Inteligente se recalcula al cambiar la primera foto.
        // El resultado en background solo se aplica si la nota sigue igual.
        if (smartOverride == null) {
            smartColorJob?.cancel()
            smartColorJob = null
            if (noteColor == NoteColor.INTELLIGENT) {
                val filename = model.images.value.firstOrNull()?.name
                if (filename != null) {
                    smartColorJob = lifecycleScope.launch {
                        val dominant = com.omgodse.notally.miscellaneous.SmartNoteColor
                            .dominant(application, model.imageRoot, filename)
                        if (dominant != null && model.color.value == NoteColor.INTELLIGENT &&
                            model.images.value.firstOrNull()?.name == filename) {
                            applyNoteAppearance(noteColor,
                                com.omgodse.notally.miscellaneous.SmartNoteColor.forTheme(
                                    dominant, this@NotallyActivity))
                        }
                    }
                }
            }
        }
        val isAmoled = com.omgodse.notally.preferences.Preferences
            .getInstance(application).isAmoledTheme()
        val colorInt = smartOverride ?:
            if (isAmoled && (noteColor == NoteColor.DEFAULT ||
                    noteColor == NoteColor.INTELLIGENT)) Color.BLACK
            else Operations.extractColor(noteColor, this)
        // Si la nota no tiene fondo propio, hereda automáticamente el de su carpeta.
        // El nombre del archivo es aleatorio; el contenido permanece cifrado.
        val effectiveWallpaper = model.wallpaperName.ifBlank {
            if (model.notebookId > 0L) {
                NotallyDatabase.getDatabase(application).getNotebookFolderDao()
                    .getNow(model.notebookId)?.wallpaperName.orEmpty()
            } else ""
        }
        val wallpaperFile = effectiveWallpaper.takeIf { it.isNotBlank() }
            ?.let { File(application.filesDir, "SecureMedia/Wallpapers/$it") }

        var backgroundColor = colorInt
        var hasWallpaper = false
        var foreground = if (androidx.core.graphics.ColorUtils.calculateLuminance(colorInt) < 0.48)
            Color.WHITE else 0xFF222225.toInt()
        if (wallpaperFile != null && wallpaperFile.exists()) {
            try {
                val bitmap = SecureMedia.openDecrypted(application, wallpaperFile).use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                        inSampleSize = 4
                    })
                }
                if (bitmap != null) {
                    val avg = NoteWallpaperDrawable.average(bitmap)
                    val darkText = androidx.core.graphics.ColorUtils.calculateLuminance(avg) > 0.6
                    foreground = if (darkText) 0xFF151924.toInt() else Color.WHITE
                    backgroundColor = avg
                    binding.root.background = NoteWallpaperDrawable(bitmap, darkText)
                    hasWallpaper = true
                }
            } catch (_: Exception) { hasWallpaper = false }
        }
        // El lienzo de escritura conserva su color real: en AMOLED se mantiene
        // #000000, sin gradientes ni velos que afecten la legibilidad.
        if (!hasWallpaper) binding.root.setBackgroundColor(colorInt)
        // Encabezado opaco: el contenido nunca debe leerse tras los iconos.
        val headerSurface = if (hasWallpaper) backgroundColor else colorInt
        binding.RecyclerView.setBackgroundColor(if (hasWallpaper) Color.TRANSPARENT else colorInt)
        binding.ToolbarContainer.setBackgroundColor(headerSurface)
        binding.Toolbar.setBackgroundColor(headerSurface)
        binding.Search.setBackgroundColor(headerSurface)
        binding.EnterTitle.setTextColor(foreground)
        binding.EnterBody.setTextColor(foreground)
        binding.EnterTitle.setHintTextColor(
            androidx.core.graphics.ColorUtils.setAlphaComponent(foreground, 165))
        binding.EnterBody.setHintTextColor(
            androidx.core.graphics.ColorUtils.setAlphaComponent(foreground, 165))
        val metadataColor = androidx.core.graphics.ColorUtils.setAlphaComponent(foreground, 180)
        listOf(binding.DateCreated, binding.WordCount,
            binding.CharacterCount, binding.StatsSeparator).forEach { view ->
            view.background = null
            view.setTextColor(metadataColor)
        }
        binding.DateIcon.imageTintList = ColorStateList.valueOf(metadataColor)
        binding.StatsChevron.imageTintList = ColorStateList.valueOf(metadataColor)
        for (i in 0 until binding.Toolbar.menu.size()) {
            binding.Toolbar.menu.getItem(i).icon?.mutate()?.setTint(foreground)
        }
        binding.Toolbar.navigationIcon?.mutate()?.setTint(foreground)
        syncActionButton?.imageTintList = ColorStateList.valueOf(foreground)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.statusBarColor = Color.TRANSPARENT
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = if (hasWallpaper) false
                else androidx.core.graphics.ColorUtils.calculateLuminance(backgroundColor) > 0.55
        }
    }

    private fun setupColor() {
        model.color.observe(this, Observer { color ->
            if (permittedToSave) markNoteEdited()
            applyNoteAppearance(color)
        })
    }


    private fun setupImages() {
        val adapter = PreviewImageAdapter(model.imageRoot) { position ->
            val intent = Intent(this, ViewImage::class.java)
            intent.putExtra(ViewImage.POSITION, position)
            intent.putExtra(Constants.SelectedBaseNote, model.id)
            startActivityForResult(intent, REQUEST_VIEW_IMAGES)
        }

        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                binding.ImagePreview.scrollToPosition(positionStart)
            }
        })

        binding.ImagePreview.setHasFixedSize(true)
        binding.ImagePreview.adapter = adapter
        binding.ImagePreview.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        PagerSnapHelper().attachToRecyclerView(binding.ImagePreview)

        model.images.observe(this, Observer { list ->
            if (permittedToSave) markNoteEdited()
            adapter.submitList(list)
            binding.ImagePreview.isVisible = list.isNotEmpty()
            if (model.color.value == NoteColor.INTELLIGENT) {
                applyNoteAppearance(NoteColor.INTELLIGENT)
            }
        })

        val dialogBinding = DialogProgressBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.adding_images)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        model.addingImages.observe(this, Observer { progress ->
            if (progress.inProgress) {
                dialog.show()
                dialogBinding.ProgressBar.max = progress.total
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    dialogBinding.ProgressBar.setProgress(progress.current, true)
                } else dialogBinding.ProgressBar.progress = progress.current
                dialogBinding.Count.text = getString(R.string.count, progress.current, progress.total)
            } else dialog.dismiss()
        })

        model.eventBus.observe(this, Observer { event ->
            event.handle { errors -> displayImageErrors(errors) }
        })
    }

    private fun displayImageErrors(errors: List<ImageError>) {
        val recyclerView = RecyclerView(this)
        recyclerView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        recyclerView.adapter = ErrorAdapter(errors)
        recyclerView.layoutManager = LinearLayoutManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            recyclerView.scrollIndicators = View.SCROLL_INDICATOR_TOP or View.SCROLL_INDICATOR_BOTTOM
        }

        val title = resources.getQuantityString(R.plurals.cant_add_images, errors.size, errors.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(recyclerView)
            .setNegativeButton(R.string.cancel, null)
            .setCancelable(false)
            .show()
    }

    private fun setupAudios() {
        val adapter = AudioAdapter { position: Int ->
            if (position != -1) {
                val audio = model.audios.value[position]
                val intent = Intent(this, PlayAudio::class.java)
                intent.putExtra(PlayAudio.AUDIO, audio)
                startActivityForResult(intent, REQUEST_PLAY_AUDIO)
            }
        }
        binding.AudioRecyclerView.adapter = adapter

        model.audios.observe(this, Observer { list ->
            if (permittedToSave) markNoteEdited()
            adapter.submitList(list)
            binding.AudioHeader.isVisible = list.isNotEmpty()
            binding.AudioRecyclerView.isVisible = list.isNotEmpty()
        })
    }


    private fun setupReminder() {
        val padding = (resources.displayMetrics.density * 16).toInt()
        val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        model.reminder.observe(this, Observer { reminder ->
            if (permittedToSave) markNoteEdited()
            if (reminder != null) {
                val date = formatter.format(reminder.timestamp)
                binding.Reminder.text = when (reminder.frequency) {
                    Frequency.ONCE -> date
                    Frequency.DAILY -> getString(R.string.repeats_daily, date)
                    Frequency.MONTHLY -> getString(R.string.repeats_monthly, date)
                }
                binding.Reminder.visibility = View.VISIBLE
                binding.DateCreated.visibility = View.VISIBLE
            } else {
                binding.Reminder.visibility = View.GONE
                binding.DateCreated.visibility = View.VISIBLE
            }
        })

        binding.Reminder.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setItems(R.array.reminder) { _, which ->
                    when (which) {
                        0 -> displayReminderDialog()
                        1 -> model.deleteReminder()
                    }
                }
                .show()
        }
    }

    private fun displayReminderDialog() {
        val dialogBinding = DialogReminderBinding.inflate(layoutInflater)

        var selectedYear = -1
        var selectedMonth = -1
        var selectedDay = -1
        var selectedHour = -1
        var selectedMinute = -1

        val dateFormatter = DateFormat.getDateInstance(DateFormat.FULL)
        val timeFormatter = DateFormat.getTimeInstance(DateFormat.SHORT)

        val dateListener = DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
            selectedYear = year
            selectedMonth = month
            selectedDay = dayOfMonth
            val calendar = Calendar.getInstance()
            calendar.clear()
            calendar.set(selectedYear, selectedMonth, selectedDay)
            dialogBinding.Date.text = dateFormatter.format(calendar.timeInMillis)
        }
        val timeListener = TimePickerDialog.OnTimeSetListener { _, hourOfDay, minute ->
            selectedHour = hourOfDay
            selectedMinute = minute
            val calendar = Calendar.getInstance()
            calendar.clear()
            calendar.set(Calendar.HOUR_OF_DAY, selectedHour)
            calendar.set(Calendar.MINUTE, selectedMinute)
            dialogBinding.Time.text = timeFormatter.format(calendar.timeInMillis)
        }
        dialogBinding.Date.setOnClickListener {
            val calendar = Calendar.getInstance()
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            val day = calendar.get(Calendar.DAY_OF_MONTH)
            val dialog = DatePickerDialog(this, dateListener, year, month, day)
            dialog.datePicker.minDate = calendar.timeInMillis
            dialog.show()
        }
        dialogBinding.Time.setOnClickListener {
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)
            TimePickerDialog(this, timeListener, hour, minute, false).show()
        }

        var selectedFrequency = 0
        val labels = resources.getStringArray(R.array.frequencies)
        dialogBinding.Frequency.text = labels[selectedFrequency]
        dialogBinding.Frequency.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.select_frequency)
                .setSingleChoiceItems(labels, selectedFrequency) { dialog, which ->
                    selectedFrequency = which
                    dialogBinding.Frequency.text = labels[selectedFrequency]
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.set_reminder)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                if (selectedYear != -1 && selectedHour != -1) {
                    val frequency = Frequency.entries[selectedFrequency]
                    val calendar = Calendar.getInstance()
                    calendar.clear()
                    calendar.set(selectedYear, selectedMonth, selectedDay, selectedHour, selectedMinute)
                    val reminder = Reminder(calendar.timeInMillis, frequency)
                    model.setReminder(reminder)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }


    private fun setupSearch() {
        binding.Search.setNavigationOnClickListener { onBackPressed() }

        val menu = binding.Search.menu
        menu.add(R.string.previous, R.drawable.previous) { model.findPrevious() }
        menu.add(R.string.next, R.drawable.next) { model.findNext() }

        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

        val transition = MaterialFade()
        transition.secondaryAnimatorProvider = null
        transition.addListener(object : Transition.TransitionListener {

            override fun onTransitionEnd(transition: Transition) {
                if (binding.Search.visibility == View.VISIBLE) {
                    binding.EnterSearchKeyword.requestFocus()
                    manager.showSoftInput(binding.EnterSearchKeyword, InputMethodManager.SHOW_IMPLICIT)
                } else {
                    binding.EnterSearchKeyword.text.clear()
                    manager.hideSoftInputFromWindow(binding.EnterSearchKeyword.windowToken, 0)
                }
            }

            override fun onTransitionStart(transition: Transition) {}

            override fun onTransitionCancel(transition: Transition) {}

            override fun onTransitionPause(transition: Transition) {}

            override fun onTransitionResume(transition: Transition) {}
        })

        model.searchEnabled.observe(this, Observer { enabled ->
            TransitionManager.beginDelayedTransition(binding.ToolbarContainer, transition)
            if (enabled) {
                binding.Search.visibility = View.VISIBLE
            } else {
                binding.Search.visibility = View.GONE
            }
        })

        model.searchBus.observe(this) { event ->
            event.handle { end ->
                binding.EnterBody.requestFocus()
                binding.EnterBody.setSelection(end)
            }
        }

        binding.EnterSearchKeyword.setText(model.query)
        binding.EnterSearchKeyword.doAfterTextChanged { text ->
            model.query = requireNotNull(text).trim().toString()
        }

        binding.EnterSearchKeyword.setOnEditorActionListener { _, actionId, event ->
            val searchPressed = (actionId == EditorInfo.IME_ACTION_SEARCH)
            val enterPressed = if (event != null) {
                event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            } else false

            if (searchPressed || enterPressed) {
                model.findNext()
            }

            return@setOnEditorActionListener false
        }
    }

    private fun updateNoteSyncIndicator() {
        val button = syncActionButton ?: return
        val note = if (model.id > 0L && !editorDirty) {
            NotallyDatabase.getDatabase(application).getBaseNoteDao().get(model.id)
        } else null
        val synced = note != null && !editorDirty && RoseDriveState.noteSynced(this, note)
        button.setImageResource(if (synced) R.drawable.refresh_cw else R.drawable.refresh_cw_off)
        button.imageTintList = ColorStateList.valueOf(
            if (synced) 0xFFF1C45B.toInt() else 0xFF98989D.toInt())
        button.contentDescription = getString(if (synced) R.string.sync_note_uploaded else R.string.sync_note_pending)
    }

    private fun forceCurrentNoteSync() {
        lifecycleScope.launch {
            // Guarda el contenido actual antes de crear la copia cifrada.
            if (permittedToSave) {
                val savedId = model.saveNote()
                if (savedId > 0L) editorDirty = false
            }
            if (BackupScheduler.force(application)) {
                Toast.makeText(this@NotallyActivity, R.string.sync_queued, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@NotallyActivity, R.string.sync_not_configured, Toast.LENGTH_LONG).show()
            }
            updateNoteSyncIndicator()
        }
    }

    private fun setupToolbar() {
        binding.Toolbar.setNavigationOnClickListener { finish() }
        val menu = binding.Toolbar.menu
        menu.clear()

        // ActionView compacto para que el indicador preceda al PIN.
        val dp = resources.displayMetrics.density
        val sync = ImageButton(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams((44 * dp).toInt(), (42 * dp).toInt())
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x33F1C45B), null, null)
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setOnClickListener { forceCurrentNoteSync() }
        }
        syncActionButton = sync
        menu.add(R.string.sync_pending).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            actionView = sync
        }
        RoseDriveState.revision.observe(this) { updateNoteSyncIndicator() }
        updateNoteSyncIndicator()

        val pin = menu.add(R.string.pin, R.drawable.pin) { model.togglePin() }
        pin.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        model.pinned.observe(this, Observer { pinned ->
            bindPinned(pin, pinned)
            if (permittedToSave) markNoteEdited()
        })
        menu.add(R.string.share, R.drawable.share) { share() }
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(R.string.more_options, R.drawable.notas_more) { showActionsSheet() }
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    }

    private fun showNoteColorPicker() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.change_color).create()
        val colorAdapter = ColorAdapter(object : ItemListener {
            override fun onClick(position: Int) {
                dialog.dismiss()
                model.color.value = NoteColor.entries[position]
            }
            override fun onLongClick(position: Int) {}
        })
        val palette = DialogColorBinding.inflate(layoutInflater)
        palette.RecyclerView.adapter = colorAdapter
        palette.IntelligentOption.setOnClickListener {
            dialog.dismiss()
            model.color.value = NoteColor.INTELLIGENT
        }
        dialog.setView(palette.root)
        dialog.show()
    }

    private fun moveToNotebook() {
        lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) {
                NotallyDatabase.getDatabase(application).getNotebookFolderDao().listOnce()
            }
            val labels = listOf(getString(R.string.notebook_unfiled)) + folders.map { it.name }
            MaterialAlertDialogBuilder(this@NotallyActivity)
                .setTitle(R.string.notebook_move)
                .setItems(labels.toTypedArray()) { _, which ->
                    val folder = folders.getOrNull(which - 1)
                    if (folder == null || folder.passwordHash.isEmpty()) {
                        model.notebookId = folder?.id ?: 0L
                        markNoteEdited()
                        applyNoteAppearance(model.color.value)
                    } else {
                        val input = EditText(this@NotallyActivity).apply {
                            inputType = InputType.TYPE_CLASS_TEXT or
                                InputType.TYPE_TEXT_VARIATION_PASSWORD
                        }
                        val dialog = MaterialAlertDialogBuilder(this@NotallyActivity)
                            .setTitle(folder.name)
                            .setView(input)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.continue_, null).create()
                        dialog.setOnShowListener {
                            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                                .setOnClickListener {
                                    val secret = input.text.toString().toCharArray()
                                    lifecycleScope.launch {
                                        val verified = withContext(Dispatchers.Default) {
                                            try { NotebookPassword.verify(secret, folder.passwordHash) }
                                            finally { secret.fill('\u0000') }
                                        }
                                        if (verified) {
                                            model.notebookId = folder.id
                                            markNoteEdited()
                                            applyNoteAppearance(model.color.value)
                                            dialog.dismiss()
                                        } else {
                                            input.error = getString(R.string.notebook_wrong_password)
                                        }
                                    }
                                }
                        }
                        dialog.show()
                    }
                }.show()
        }
    }

    /** Proporcionada por el editor de notas; no existe en las listas de tareas. */
    protected open fun showIncomingLinks() {}

    private fun showActionsSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetSurface = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0xFFF5F5F7.toInt())
        val sheetText = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1C1E.toInt())
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad + (12 * resources.displayMetrics.density).toInt())
            background = GradientDrawable().apply {
                setColor(sheetSurface)
                val radius = 28f * resources.displayMetrics.density
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
        }
        val title = TextView(this).apply {
            text = getString(R.string.note_actions)
            setTextColor(sheetText)
            textSize = 19f
            setPadding(12, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }
        container.addView(title)
        fun action(titleId: Int, iconId: Int, destructive: Boolean = false, handler: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = (55 * resources.displayMetrics.density).toInt()
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33F1C45B), null, null
                )
            }
            val icon = ImageView(this).apply {
                setImageResource(iconId)
                imageTintList = ColorStateList.valueOf(if (destructive) 0xFFFF453A.toInt() else 0xFFF1C45B.toInt())
            }
            row.addView(icon, LinearLayout.LayoutParams((25 * resources.displayMetrics.density).toInt(), (25 * resources.displayMetrics.density).toInt()).apply {
                marginStart = (10 * resources.displayMetrics.density).toInt()
                marginEnd = (16 * resources.displayMetrics.density).toInt()
            })
            val label = TextView(this).apply {
                setText(titleId)
                setTextColor(sheetText)
                textSize = 15f
                typeface = androidx.core.content.res.ResourcesCompat.getFont(this@NotallyActivity, R.font.notas_sans)
            }
            row.addView(label)
            row.setOnClickListener { dialog.dismiss(); handler() }
            container.addView(row)
        }
        action(R.string.labels, R.drawable.label) { label() }
        if (model.type == Type.NOTE) {
            action(R.string.search, R.drawable.search) { model.searchEnabled.value = true }
            action(R.string.backlinks, R.drawable.search) { showIncomingLinks() }
        }
        action(R.string.add_images, R.drawable.add_images) { selectImages() }
        action(R.string.draw_with_finger, R.drawable.notas_draw) {
            startActivityForResult(Intent(this, DrawingActivity::class.java), REQUEST_ADD_IMAGES)
        }
        action(R.string.change_color, R.drawable.change_color) { showNoteColorPicker() }
        action(R.string.wallpaper_custom, R.drawable.add_images) {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            }
            startActivityForResult(intent, REQUEST_NOTE_WALLPAPER)
        }
        if (model.wallpaperName.isNotBlank()) {
            action(R.string.wallpaper_remove, R.drawable.delete, destructive = true) {
                model.wallpaperName = ""
                markNoteEdited()
                applyNoteAppearance(model.color.value)
            }
        }
        action(R.string.notebook_move, R.drawable.notas_nav_folder) { moveToNotebook() }
        if (model.id > 0L && PinSecurity.noteLocked(this, model.id)) {
            val key = PinSecurity.noteKey(model.id)
            action(
                if (BiometricSecurity.isEnabled(this, key)) R.string.disable_note_biometric
                else R.string.enable_note_biometric, R.drawable.notas_lock
            ) {
                PinDialogs.check(this, key, onVerified = {
                    if (BiometricSecurity.isEnabled(this, key)) {
                        BiometricSecurity.setEnabled(this, key, false)
                    } else {
                        BiometricSecurity.authenticate(this,
                            getString(R.string.biometric_unlock), onAuthenticated = {
                                BiometricSecurity.setEnabled(this, key, true)
                            }, onFallback = {})
                    }
                })
            }
            action(R.string.remove_note_pin, R.drawable.notas_lock) {
                PinDialogs.check(this, PinSecurity.noteKey(model.id), onVerified = {
                    PinSecurity.removeNotePin(this, model.id)
                    WidgetProvider.sendBroadcast(application, model.id)
                })
            }
        } else {
            action(R.string.lock_note, R.drawable.notas_lock) {
                if (!model.hasMeaningfulContent()) {
                    Toast.makeText(this, R.string.notas_add_content_before_lock, Toast.LENGTH_SHORT).show()
                } else {
                    PinDialogs.setup(this) { pin ->
                        lifecycleScope.launch {
                            val savedId = model.saveNote()
                            if (savedId > 0L) {
                                PinSecurity.setNotePin(this@NotallyActivity, savedId, pin)
                                WidgetProvider.sendBroadcast(application, savedId)
                            }
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) action(R.string.record_audio, R.drawable.record_audio) { checkAudioPermission() }
        action(R.string.set_reminder, R.drawable.reminder) { checkNotificationPermission() }
        when (model.folder) {
            Folder.NOTES -> {
                action(R.string.archive, R.drawable.archive) { archive() }
                action(R.string.delete, R.drawable.delete, destructive = true) { delete() }
            }
            Folder.DELETED -> {
                action(R.string.restore, R.drawable.restore) { restore() }
                action(R.string.delete_forever, R.drawable.delete, destructive = true) { deleteForever() }
            }
            Folder.ARCHIVED -> {
                action(R.string.unarchive, R.drawable.unarchive) { restore() }
                action(R.string.delete, R.drawable.delete, destructive = true) { delete() }
            }
        }
        // Las acciones aumentaron con los backlinks; mantenerlas desplazables
        // en pantallas pequeñas sin recortar las últimas opciones.
        dialog.setContentView(android.widget.ScrollView(this).apply {
            isFillViewport = false
            addView(container)
        })
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
                backgroundTintList = null
                background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                clipToOutline = false
            }
            dialog.window?.navigationBarColor = sheetSurface
        }
        dialog.show()
    }

    private fun initialiseBinding() {
        binding = ActivityNotallyBinding.inflate(layoutInflater)
        when (type) {
            Type.NOTE -> {
                binding.AddItem.visibility = View.GONE
                binding.RecyclerView.visibility = View.GONE
            }
            Type.LIST -> {
                binding.EnterBody.visibility = View.GONE
            }
        }
        setupCollapsibleMetadata()

        val bodySize = TextSize.getDisplayBodySize(model.textSize)

        binding.EnterTitle.typeface = androidx.core.content.res.ResourcesCompat.getFont(
            this, R.font.notas_sans)
        binding.EnterTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, TextSize.getEditTitleSize(model.textSize))
        binding.DateCreated.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        binding.Reminder.setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySize)
        binding.EnterBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, TextSize.getEditBodySize(model.textSize))

        model.labels.observe(this, Observer { labels ->
            if (permittedToSave) markNoteEdited()
            Operations.bindLabels(binding.LabelGroup, labels, bodySize)
        })

        setupColor()
        setupImages()
        setupAudios()
        setupReminder()
        setupToolbar()
        setupSearch()
        updateCharacterCount()

        binding.root.isSaveFromParentEnabled = false
    }


    private fun bindPinned(item: MenuItem, pinned: Boolean) {
        val icon: Int
        val title: Int
        if (pinned) {
            icon = R.drawable.unpin
            title = R.string.unpin
        } else {
            icon = R.drawable.pin
            title = R.string.pin
        }
        item.setTitle(title)
        item.setIcon(icon)
    }

    companion object {
        private const val REQUEST_NOTE_WALLPAPER = 74
        private const val REQUEST_ADD_IMAGES = 30
        private const val REQUEST_VIEW_IMAGES = 31
        private const val REQUEST_NOTIFICATION_PERMISSION = 32
        private const val REQUEST_SELECT_LABELS = 33
        private const val REQUEST_RECORD_AUDIO = 34
        private const val REQUEST_PLAY_AUDIO = 35
        private const val REQUEST_AUDIO_PERMISSION = 36
        private const val REQUEST_ALARM_PERMISSION = 37
    }
}