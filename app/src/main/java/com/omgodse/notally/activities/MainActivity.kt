package com.omgodse.notally.activities

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.widget.EditText
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.view.Gravity
import android.view.ViewGroup
import android.text.InputType
import com.omgodse.notally.security.NotebookPassword
import com.omgodse.notally.security.BiometricSecurity
import com.omgodse.notally.room.NotebookFolder
import com.omgodse.notally.miscellaneous.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.net.Uri
import android.os.Bundle
import android.print.PostPDFGenerator
import android.view.Menu
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.forEach
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.transition.TransitionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialFade
import com.omgodse.notally.MenuDialog
import com.omgodse.notally.R
import com.omgodse.notally.security.NoteWallpaperStore
import com.omgodse.notally.backup.RoseDriveState
import com.omgodse.notally.backup.BackupScheduler
import com.omgodse.notally.databinding.ActivityMainBinding
import com.omgodse.notally.databinding.DialogColorBinding
import com.omgodse.notally.miscellaneous.Operations
import com.omgodse.notally.miscellaneous.add
import com.omgodse.notally.miscellaneous.applySpans
import com.omgodse.notally.recyclerview.ItemListener
import com.omgodse.notally.recyclerview.adapter.ColorAdapter
import com.omgodse.notally.room.BaseNote
import com.omgodse.notally.room.Color
import com.omgodse.notally.room.Folder
import com.omgodse.notally.room.Type
import com.omgodse.notally.viewmodels.BaseNoteModel
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : NotasBaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var configuration: AppBarConfiguration

    private val model: BaseNoteModel by viewModels()
    private var selectedProtectedNotebook = false
    private var pendingWallpaperFolderId: Long? = null
    private val wallpaperPicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            val folderId = pendingWallpaperFolderId
            pendingWallpaperFolderId = null
            if (folderId != null && uri != null) saveNotebookWallpaper(folderId, uri)
        }

    override fun onBackPressed() {
        if (model.actionMode.enabled.value) {
            model.actionMode.close(true)
        } else if (navController.currentDestination?.id == R.id.Notes &&
            model.selectedNotebookId.value != -1L) {
            selectNotebook(-1L, getString(R.string.notebook_all), false)
        } else super.onBackPressed()
    }

    override fun onStop() {
        // No conservar acceso a carpetas protegidas después de salir de Inicio.
        if (selectedProtectedNotebook) {
            selectNotebook(-1L, getString(R.string.notebook_all), false)
        }
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(configuration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.Toolbar)
        setupBottomPill()
        setupActionMode()
        setupNavigation()
        setupSearch()
        setupSyncStatus()
        binding.OpenFolders.setOnClickListener { showNotebookFolders() }
        binding.CreateFolder.setOnClickListener { createNotebookDialog() }
        intent.getLongExtra(EXTRA_LINKED_FOLDER, -1L)
            .takeIf { it > 0L }?.let(::openLinkedFolder)
    }

    /** Solo se navega a una carpeta protegida tras verificar su contraseña. */
    private fun openLinkedFolder(folderId: Long) {
        lifecycleScope.launch {
            val folder = withContext(Dispatchers.IO) {
                com.omgodse.notally.room.NotallyDatabase.getDatabase(application)
                    .getNotebookFolderDao().getNow(folderId)
            }
            if (folder == null) {
                Toast.makeText(this@MainActivity, R.string.link_target_missing,
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            if (folder.passwordHash.isBlank()) {
                selectNotebook(folder.id, folder.name, false)
            } else requestNotebookPassword(folder) {
                selectNotebook(folder.id, folder.name, true)
            }
        }
    }

    private fun setupSyncStatus() {
        fun refresh() {
            if (navController.currentDestination?.id != R.id.Notes) return
            val complete = model.allNotes.value?.let { RoseDriveState.allSynced(this, it) } ?: false
            binding.SyncStatus.setImageResource(if (complete) R.drawable.refresh_cw else R.drawable.refresh_cw_off)
            binding.SyncStatus.imageTintList = ColorStateList.valueOf(
                if (complete) 0xFFF1C45B.toInt() else 0xFF98989D.toInt())
            binding.SyncStatus.contentDescription =
                getString(if (complete) R.string.sync_up_to_date else R.string.sync_pending)
        }
        model.allNotes.observe(this) { refresh() }
        RoseDriveState.revision.observe(this) { refresh() }
        binding.SyncStatus.setOnClickListener { requestManualBackup() }
        refresh()
    }

    private fun activeScreen(): androidx.fragment.app.Fragment? =
        (supportFragmentManager.findFragmentById(R.id.NavHostFragment) as? NavHostFragment)
            ?.childFragmentManager?.primaryNavigationFragment

    private fun requestManualBackup() {
        if (BackupScheduler.force(application)) {
            Toast.makeText(this, R.string.sync_queued, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.sync_not_configured, Toast.LENGTH_LONG).show()
            if (navController.currentDestination?.id != R.id.Settings) {
                navController.navigate(R.id.Settings)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EXPORT_FILE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                model.writeCurrentFileToUri(uri)
            }
        }
    }


    private fun setupBottomPill() {
        binding.TakeNote.setOnClickListener {
            startActivity(Intent(this, TakeNote::class.java).apply {
                putExtra(Constants.SelectedNotebookId,
                    model.selectedNotebookId.value?.takeIf { id -> id > 0 } ?: 0L)
            })
        }
        binding.MakeList.setOnClickListener {
            startActivity(Intent(this, MakeList::class.java).apply {
                putExtra(Constants.SelectedNotebookId,
                    model.selectedNotebookId.value?.takeIf { id -> id > 0 } ?: 0L)
            })
        }
        binding.BottomSearch.setOnClickListener {
            if (navController.currentDestination?.id == R.id.Notes) {
                navController.navigate(R.id.NotesToSearch)
            }
        }
        listOf(binding.BottomSearch, binding.TakeNote, binding.MakeList)
            .forEach(::installPillPressAnimation)
    }

    private fun installPillPressAnimation(button: View) {
        // Efecto de salto hacia el usuario (escala), sin desplazamiento vertical.
        // Mantener escala dentro de la zona táctil para que no se recorte el glifo.
        val front = 5f * resources.displayMetrics.density
        button.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.animate().cancel()
                    target.animate()
                        .translationZ(front).scaleX(1.12f).scaleY(1.12f)
                        .setDuration(110L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.animate().cancel()
                    target.animate()
                        .translationZ(0f).scaleX(1f).scaleY(1f)
                        .setDuration(215L)
                        .setInterpolator(android.view.animation.OvershootInterpolator(0.7f))
                        .start()
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        target.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }
            }
            false
        }
    }

    private fun selectNotebook(id: Long, title: String, isProtected: Boolean) {
        model.setActiveNotebook(id)
        selectedProtectedNotebook = isProtected
        if (navController.currentDestination?.id != R.id.Notes) {
            navController.navigate(R.id.Notes)
        }
        binding.SectionTitle.text = if (id == -1L) {
            getString(R.string.internal_app_name)
        } else title
        binding.FoldersLabel.text = if (id == -1L) getString(R.string.notebook_folders) else title
    }

    /** Selector Apple Notes con carpetas protegidas y acceso principal sin carpetas. */
    private fun showNotebookFolders() {
        lifecycleScope.launch {
            val folders = model.getNotebookFolders()
            if (isFinishing || isDestroyed) return@launch
            val dialog = BottomSheetDialog(this@MainActivity)
            val dp = resources.displayMetrics.density
            fun px(v: Int) = (dp * v).toInt()
            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(20), px(22), px(20), px(32))
                background = GradientDrawable().apply {
                    setColor(com.google.android.material.color.MaterialColors.getColor(
                        this@MainActivity, com.google.android.material.R.attr.colorSurface,
                        0xFF242429.toInt()))
                    cornerRadius = px(28).toFloat()
                }
            }
            val heading = TextView(this@MainActivity).apply {
                setText(R.string.notebook_folders)
                textSize = 25f
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this@MainActivity, com.google.android.material.R.attr.colorOnSurface,
                    0xFFFFFFFF.toInt()))
                setPadding(px(10), 0, 0, px(18))
            }
            container.addView(heading)
            fun row(name: String, detail: String, folder: NotebookFolder?, action: () -> Unit) {
                val item = LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    minimumHeight = px(62)
                    setPadding(px(14), px(8), px(12), px(8))
                    background = android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(0x33F1C45B), null, null)
                }
                val folderIcon = ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.notas_nav_folder)
                    imageTintList = ColorStateList.valueOf(0xFFF1C45B.toInt())
                }
                item.addView(folderIcon, LinearLayout.LayoutParams(px(27), px(27)).apply {
                    marginEnd = px(16)
                })
                val text = TextView(this@MainActivity).apply {
                    text = name + if (detail.isNotBlank()) "  ·  $detail" else ""
                    textSize = 16f
                    setTextColor(com.google.android.material.color.MaterialColors.getColor(
                        this@MainActivity, com.google.android.material.R.attr.colorOnSurface,
                        0xFFFFFFFF.toInt()))
                }
                item.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                if (folder?.passwordHash?.isNotEmpty() == true) {
                    item.addView(ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.notas_lock)
                        imageTintList = ColorStateList.valueOf(0xFFF1C45B.toInt())
                    }, LinearLayout.LayoutParams(px(17), px(17)).apply { marginEnd = px(12) })
                }
                if (folder != null) {
                    val options = ImageView(this@MainActivity).apply {
                        setImageResource(android.R.drawable.ic_menu_more)
                        imageTintList = ColorStateList.valueOf(0xFFF1C45B.toInt())
                        contentDescription = getString(R.string.notebook_options)
                        setPadding(px(9), px(9), px(9), px(9))
                        background = android.graphics.drawable.RippleDrawable(
                            ColorStateList.valueOf(0x33F1C45B), null, null)
                        setOnClickListener {
                            dialog.dismiss()
                            if (folder.passwordHash.isNotBlank()) {
                                requestNotebookPassword(folder) { showNotebookOptions(folder) }
                            } else showNotebookOptions(folder)
                        }
                    }
                    item.addView(options, LinearLayout.LayoutParams(px(45), px(45)))
                }
                item.setOnClickListener { dialog.dismiss(); action() }
                container.addView(item)
            }
            row(getString(R.string.notebook_all), "", null) {
                selectNotebook(-1L, getString(R.string.notebook_all), false)
            }
            row(getString(R.string.notebook_unfiled), "", null) {
                selectNotebook(0L, getString(R.string.notebook_unfiled), false)
            }
            folders.forEach { folder ->
                row(folder.name, "", folder) {
                    if (folder.passwordHash.isEmpty()) {
                        selectNotebook(folder.id, folder.name, false)
                    } else requestNotebookPassword(folder) {
                        selectNotebook(folder.id, folder.name, true)
                    }
                }
            }
            dialog.setContentView(container)
            dialog.setOnShowListener {
                dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    ?.setBackgroundColor(AndroidColor.TRANSPARENT)
            }
            dialog.show()
        }
    }

    private fun showNotebookOptions(folder: NotebookFolder) {
        val items = mutableListOf(
            getString(R.string.notebook_wallpaper) to "wallpaper")
        if (folder.wallpaperName.isNotBlank()) {
            items.add(getString(R.string.notebook_remove_wallpaper) to "remove_wallpaper")
        }
        if (folder.passwordHash.isNotBlank() && BiometricSecurity.supported(this)) {
            items.add(getString(
                if (BiometricSecurity.isEnabled(this, "folder:${folder.id}"))
                    R.string.disable_folder_biometric else R.string.enable_folder_biometric
            ) to "biometric")
        }
        items.add(getString(R.string.notebook_delete) to "delete")
        MaterialAlertDialogBuilder(this)
            .setTitle(folder.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, selected ->
                when (items[selected].second) {
                    "wallpaper" -> {
                        pendingWallpaperFolderId = folder.id
                        wallpaperPicker.launch("image/*")
                    }
                    "remove_wallpaper" -> lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            com.omgodse.notally.room.NotallyDatabase.getDatabase(application)
                                .getNotebookFolderDao().updateWallpaper(folder.id, "")
                        }
                        Toast.makeText(this@MainActivity,
                            R.string.notebook_wallpaper_saved, Toast.LENGTH_SHORT).show()
                    }
                    "biometric" -> {
                        val key = "folder:${folder.id}"
                        if (BiometricSecurity.isEnabled(this, key)) {
                            BiometricSecurity.setEnabled(this, key, false)
                        } else {
                            BiometricSecurity.authenticate(this, folder.name,
                                onAuthenticated = {
                                    BiometricSecurity.setEnabled(this, key, true)
                                }, onFallback = {})
                        }
                    }
                    "delete" -> confirmNotebookDeletion(folder)
                }
            }.show()
    }

    private fun confirmNotebookDeletion(folder: NotebookFolder) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notebook_delete)
            .setMessage(getString(R.string.notebook_delete_confirmation, folder.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.notebook_delete, null)
            .create().apply {
                setOnShowListener {
                    getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                        getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = false
                        lifecycleScope.launch {
                            try {
                                val removed = model.deleteNotebookKeepingNotes(folder.id)
                                if (removed) {
                                    BiometricSecurity.setEnabled(
                                        this@MainActivity, "folder:${folder.id}", false)
                                    // Restablecer primero la carpeta activa: notas conservadas.
                                    if (model.selectedNotebookId.value == folder.id) {
                                        selectNotebook(-1L, getString(R.string.notebook_all), false)
                                    }
                                    dismiss()
                                    Toast.makeText(this@MainActivity,
                                        R.string.notebook_deleted, Toast.LENGTH_SHORT).show()
                                } else {
                                    dismiss()
                                    Toast.makeText(this@MainActivity,
                                        R.string.notebook_not_found, Toast.LENGTH_SHORT).show()
                                }
                            } catch (error: Exception) {
                                android.util.Log.e("NotasFolders", "Unable to remove folder", error)
                                getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                                    .isEnabled = true
                                Toast.makeText(this@MainActivity,
                                    R.string.notebook_delete_error, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                show()
            }
    }

    private fun saveNotebookWallpaper(folderId: Long, uri: Uri) {
        lifecycleScope.launch {
            var filename: String? = null
            try {
                filename = withContext(Dispatchers.IO) {
                    NoteWallpaperStore.import(application, uri)
                }
                val saved = requireNotNull(filename)
                withContext(Dispatchers.IO) {
                    com.omgodse.notally.room.NotallyDatabase.getDatabase(application)
                        .getNotebookFolderDao().updateWallpaper(folderId, saved)
                }
                Toast.makeText(this@MainActivity, R.string.notebook_wallpaper_saved,
                    Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                android.util.Log.e("RoseWallpaper", "Folder wallpaper import failed", error)
                filename?.let { File(application.filesDir, "SecureMedia/Wallpapers/$it").delete() }
                Toast.makeText(this@MainActivity, R.string.wallpaper_invalid,
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Diálogos de Notas con campos Material delineados. Se usa un contenedor
     * con márgenes reales en dp y el tema actual (Claro/Oscuro/AMOLED).
     */
    private fun notebookField(hint: Int, password: Boolean = false):
        Pair<TextInputLayout, TextInputEditText> {
        val input = TextInputEditText(this).apply {
            isSingleLine = true
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            if (!password) filters = arrayOf(android.text.InputFilter.LengthFilter(80))
            setTextColor(com.google.android.material.color.MaterialColors.getColor(
                this@MainActivity, com.google.android.material.R.attr.colorOnSurface,
                android.graphics.Color.WHITE))
        }
        val layout = TextInputLayout(this).apply {
            this.hint = getString(hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxStrokeColor = 0xFFF1C45B.toInt()
            setBoxCornerRadii(14f * resources.displayMetrics.density,
                14f * resources.displayMetrics.density,
                14f * resources.displayMetrics.density,
                14f * resources.displayMetrics.density)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        return layout to input
    }

    private fun notebookDialogView(field: TextInputLayout): View {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * dp).toInt(), (12 * dp).toInt(),
                (22 * dp).toInt(), (6 * dp).toInt())
            addView(field, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun createNotebookDialog() {
        val (field, input) = notebookField(R.string.notebook_name)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notebook_new)
            .setView(notebookDialogView(field))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    field.error = getString(R.string.notebook_name_required)
                    return@setOnClickListener
                }
                dialog.dismiss()
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.notebook_protect)
                    .setMessage(R.string.notebook_password_optional)
                    .setNegativeButton(R.string.notebook_without_password) { _, _ ->
                        saveNotebook(name, null)
                    }
                    .setPositiveButton(R.string.notebook_protect) { _, _ ->
                        val (passwordField, passwordInput) =
                            notebookField(R.string.notebook_password, true)
                        val passwordDialog = MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.notebook_protect)
                            .setView(notebookDialogView(passwordField))
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.save, null)
                            .create()
                        passwordDialog.setOnShowListener {
                            passwordDialog.getButton(
                                android.content.DialogInterface.BUTTON_POSITIVE
                            ).setOnClickListener {
                                val secret = passwordInput.text?.toString().orEmpty()
                                if (secret.length < 12) {
                                    passwordField.error = getString(R.string.notebook_password)
                                } else {
                                    passwordInput.text?.clear()
                                    passwordDialog.dismiss()
                                    saveNotebook(name, secret)
                                }
                            }
                        }
                        passwordDialog.show()
                    }.show()
            }
        }
        dialog.show()
    }

    private fun saveNotebook(name: String, password: String?) {
        lifecycleScope.launch {
            val hash = withContext(Dispatchers.Default) {
                password?.toCharArray()?.let { chars ->
                    try { NotebookPassword.hash(chars) } finally { chars.fill('\u0000') }
                }.orEmpty()
            }
            val id = model.createNotebook(name, hash)
            selectNotebook(id, name, hash.isNotEmpty())
            Toast.makeText(this@MainActivity, R.string.notebook_created,
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotebookPassword(folder: NotebookFolder, onVerified: () -> Unit) =
        requestNotebookPasswordInternal(folder, onVerified, false)

    private fun requestNotebookPasswordInternal(
        folder: NotebookFolder, onVerified: () -> Unit, bypassBiometric: Boolean
    ) {
        val key = "folder:${folder.id}"
        if (!bypassBiometric && BiometricSecurity.isEnabled(this, key)) {
            BiometricSecurity.authenticate(this, folder.name, onVerified,
                onFallback = { requestNotebookPasswordInternal(folder, onVerified, true) })
            return
        }
        val (field, input) = notebookField(R.string.notebook_password, true)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(folder.name)
            .setView(notebookDialogView(field))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_, null).create()
        dialog.setOnShowListener {
            val positive = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val secret = input.text?.toString().orEmpty().toCharArray()
                if (secret.isEmpty()) {
                    field.error = getString(R.string.notebook_wrong_password)
                    return@setOnClickListener
                }
                positive.isEnabled = false
                lifecycleScope.launch {
                    val valid = withContext(Dispatchers.Default) {
                        try { NotebookPassword.verify(secret, folder.passwordHash) }
                        finally { secret.fill('\u0000') }
                    }
                    positive.isEnabled = true
                    if (valid) {
                        input.text?.clear()
                        dialog.dismiss()
                        onVerified()
                    } else field.error = getString(R.string.notebook_wrong_password)
                }
            }
        }
        dialog.show()
    }

    private fun setupActionMode() {
        binding.ActionMode.setNavigationOnClickListener { model.actionMode.close(true) }

        val transition = MaterialFade()
        transition.secondaryAnimatorProvider = null

        transition.excludeTarget(binding.NavHostFragment, true)
        transition.excludeChildren(binding.NavHostFragment, true)
        transition.excludeTarget(binding.BottomPill, true)
        transition.excludeTarget(binding.NavigationView, true)

        model.actionMode.enabled.observe(this) { enabled ->
            TransitionManager.beginDelayedTransition(binding.RelativeLayout, transition)
            if (enabled) {
                binding.Toolbar.visibility = View.GONE
                binding.ActionMode.visibility = View.VISIBLE
                binding.DrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                binding.BottomPill.visibility = View.GONE
                binding.FoldersBar.visibility = View.GONE
            } else {
                binding.Toolbar.visibility = View.VISIBLE
                binding.ActionMode.visibility = View.GONE
                binding.DrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                binding.BottomPill.visibility = if (navController.currentDestination?.id == R.id.Notes) View.VISIBLE else View.GONE
                binding.FoldersBar.visibility = if (navController.currentDestination?.id == R.id.Notes) View.VISIBLE else View.GONE
            }
        }

        val menu = binding.ActionMode.menu
        val pinned = menu.add(R.string.pin, R.drawable.pin) {}
        val share = menu.add(R.string.share, R.drawable.share) { share() }
        val labels = menu.add(R.string.labels, R.drawable.label) { label() }

        val export = createExportMenu(menu)

        val changeColor = menu.add(R.string.change_color, R.drawable.change_color) { changeColor() }
        val copy = menu.add(R.string.make_a_copy, R.drawable.copy) { model.copyBaseNote() }
        val delete = menu.add(R.string.delete, R.drawable.delete) { model.moveBaseNotes(Folder.DELETED) }
        val archive = menu.add(R.string.archive, R.drawable.archive) { model.moveBaseNotes(Folder.ARCHIVED) }
        val restore = menu.add(R.string.restore, R.drawable.restore) { model.moveBaseNotes(Folder.NOTES) }
        val unarchive = menu.add(R.string.unarchive, R.drawable.unarchive) { model.moveBaseNotes(Folder.NOTES) }
        val deleteForever = menu.add(R.string.delete_forever, R.drawable.delete) { deleteForever() }

        model.actionMode.count.observe(this) { count ->
            if (count == 0) {
                menu.forEach { item -> item.setVisible(false) }
            } else {
                binding.ActionMode.title = count.toString()

                val baseNote = model.actionMode.getFirstNote()
                if (count == 1) {
                    if (baseNote.pinned) {
                        pinned.setTitle(R.string.unpin)
                        pinned.setIcon(R.drawable.unpin)
                    } else {
                        pinned.setTitle(R.string.pin)
                        pinned.setIcon(R.drawable.pin)
                    }
                    pinned.onClick { model.pinBaseNote(!baseNote.pinned) }
                }

                pinned.setVisible(count == 1)
                share.setVisible(count == 1)
                labels.setVisible(count == 1)
                export.setVisible(count == 1)
                changeColor.setVisible(true)
                copy.setVisible(true)

                val folder = baseNote.folder
                delete.setVisible(folder == Folder.NOTES || folder == Folder.ARCHIVED)
                archive.setVisible(folder == Folder.NOTES)
                restore.setVisible(folder == Folder.DELETED)
                unarchive.setVisible(folder == Folder.ARCHIVED)
                deleteForever.setVisible(folder == Folder.DELETED)
            }
        }
    }

    private fun createExportMenu(menu: Menu): MenuItem {
        val export = menu.addSubMenu(R.string.export)
        export.setIcon(R.drawable.export)
        export.item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)

        export.add("PDF").onClick { exportToPDF() }
        export.add("TXT").onClick { exportToTXT() }
        export.add("JSON").onClick { exportToJSON() }
        export.add("HTML").onClick { exportToHTML() }

        return export.item
    }

    fun MenuItem.onClick(function: () -> Unit) {
        setOnMenuItemClickListener {
            function()
            return@setOnMenuItemClickListener false
        }
    }


    private fun share() {
        val baseNote = model.actionMode.getFirstNote()
        val body = when (baseNote.type) {
            Type.NOTE -> baseNote.body.applySpans(baseNote.spans)
            Type.LIST -> Operations.getBody(baseNote.items)
        }
        Operations.shareNote(this, baseNote.title, body)
    }

    private fun changeColor() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.change_color)
            .create()

        val colorAdapter = ColorAdapter(object : ItemListener {
            override fun onClick(position: Int) {
                dialog.dismiss()
                val color = Color.entries[position]
                model.colorBaseNote(color)
            }

            override fun onLongClick(position: Int) {}
        })

        val dialogBinding = DialogColorBinding.inflate(layoutInflater)
        dialogBinding.RecyclerView.adapter = colorAdapter

        dialog.setView(dialogBinding.root)
        dialog.show()
    }

    private fun deleteForever() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.delete_selected_notes)
            .setPositiveButton(R.string.delete) { _, _ -> model.deleteBaseNotes() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }


    private fun label() {
        val baseNote = model.actionMode.getFirstNote()
        lifecycleScope.launch {
            val labels = model.getAllLabels()
            if (labels.isNotEmpty()) {
                displaySelectLabelsDialog(labels, baseNote)
            } else {
                model.actionMode.close(true)
                navigateWithAnimation(R.id.Labels)
            }
        }
    }

    private fun displaySelectLabelsDialog(labels: Array<String>, baseNote: BaseNote) {
        val checkedPositions = BooleanArray(labels.size) { index -> baseNote.labels.contains(labels[index]) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.labels)
            .setNegativeButton(R.string.cancel, null)
            .setMultiChoiceItems(labels, checkedPositions) { _, which, isChecked -> checkedPositions[which] = isChecked }
            .setPositiveButton(R.string.save) { _, _ ->
                val new = ArrayList<String>()
                checkedPositions.forEachIndexed { index, checked ->
                    if (checked) {
                        val label = labels[index]
                        new.add(label)
                    }
                }
                model.updateBaseNoteLabels(new, baseNote.id)
            }
            .show()
    }


    private fun exportToPDF() {
        val baseNote = model.actionMode.getFirstNote()
        model.getPDFFile(baseNote, object : PostPDFGenerator.OnResult {

            override fun onSuccess(file: File) {
                showFileOptionsDialog(file, "application/pdf")
            }

            override fun onFailure(message: CharSequence?) {
                Toast.makeText(this@MainActivity, R.string.something_went_wrong, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun exportToTXT() {
        val baseNote = model.actionMode.getFirstNote()
        lifecycleScope.launch {
            val file = model.getTXTFile(baseNote)
            showFileOptionsDialog(file, "text/plain")
        }
    }

    private fun exportToJSON() {
        val baseNote = model.actionMode.getFirstNote()
        lifecycleScope.launch {
            val file = model.getJSONFile(baseNote)
            showFileOptionsDialog(file, "application/json")
        }
    }

    private fun exportToHTML() {
        val baseNote = model.actionMode.getFirstNote()
        lifecycleScope.launch {
            val file = model.getHTMLFile(baseNote)
            showFileOptionsDialog(file, "text/html")
        }
    }


    private fun showFileOptionsDialog(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)

        MenuDialog(this)
            .add(R.string.share) { shareFile(uri, mimeType) }
            .add(R.string.view_file) { viewFile(uri, mimeType) }
            .add(R.string.save_to_device) { saveFileToDevice(file, mimeType) }
            .show()
    }

    private fun viewFile(uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, mimeType)
        intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION

        val chooser = Intent.createChooser(intent, getString(R.string.view_note))
        startActivity(chooser)
    }

    private fun shareFile(uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = mimeType
        intent.putExtra(Intent.EXTRA_STREAM, uri)

        val chooser = Intent.createChooser(intent, null)
        startActivity(chooser)
    }

    private fun saveFileToDevice(file: File, mimeType: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.type = mimeType
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.putExtra(Intent.EXTRA_TITLE, file.nameWithoutExtension)

        model.currentFile = file
        startActivityForResult(intent, REQUEST_EXPORT_FILE)
    }


    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.NavHostFragment) as NavHostFragment
        navController = navHostFragment.navController
        val topLevelDestinations = setOf(R.id.Notes, R.id.Labels, R.id.Deleted, R.id.Archived, R.id.Settings)
        configuration = AppBarConfiguration(topLevelDestinations, binding.DrawerLayout)
        setupActionBarWithNavController(navController, configuration)
        binding.DrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        binding.Toolbar.navigationIcon = null
        binding.Toolbar.title = ""
        binding.Toolbar.setNavigationOnClickListener { onSupportNavigateUp() }
        binding.OpenSections.setOnClickListener {
            val top = setOf(R.id.Notes, R.id.Labels, R.id.Deleted, R.id.Archived, R.id.Settings)
            if (navController.currentDestination?.id in top) showNavigationSheet()
        }

        var fragmentIdToLoad: Int? = null
        binding.NavigationView.onItemClickListener = { id ->
            fragmentIdToLoad = id
            binding.DrawerLayout.closeDrawer(GravityCompat.START)
        }

        binding.DrawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {

            override fun onDrawerClosed(drawerView: View) {
                if (fragmentIdToLoad != null && navController.currentDestination?.id != fragmentIdToLoad) {
                    navigateWithAnimation(requireNotNull(fragmentIdToLoad))
                }
            }
        })

        navController.addOnDestinationChangedListener { _, destination, _ ->
            fragmentIdToLoad = destination.id
            binding.NavigationView.setCheckedItem(destination.id)
            handleDestinationChange(destination)
        }
    }

    private fun handleDestinationChange(destination: NavDestination) {
        val top = destination.id in setOf(R.id.Notes, R.id.Labels, R.id.Deleted, R.id.Archived, R.id.Settings)
        binding.Toolbar.navigationIcon = if (top) null else androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.previous)
        binding.Toolbar.title = ""
        binding.SectionTitle.text = if (destination.id == R.id.Notes) getString(R.string.internal_app_name) else (destination.label ?: getString(R.string.notes))
        binding.SectionTitle.visibility = if (destination.id == R.id.Search) View.GONE else View.VISIBLE
        binding.OpenSections.visibility = if (destination.id == R.id.Search) View.GONE else View.VISIBLE
        binding.SyncStatus.visibility =
            if (destination.id in setOf(R.id.Notes, R.id.Labels, R.id.Deleted)) View.VISIBLE else View.GONE
        when (destination.id) {
            R.id.Labels -> {
                binding.SyncStatus.setImageResource(R.drawable.add)
                binding.SyncStatus.imageTintList = ColorStateList.valueOf(0xFFF1C45B.toInt())
                binding.SyncStatus.contentDescription = getString(R.string.add_label)
                binding.SyncStatus.setOnClickListener {
                    activeScreen()?.let { screen ->
                        (screen as? com.omgodse.notally.fragments.Labels)?.displayAddLabelDialog()
                    }
                }
            }
            R.id.Deleted -> {
                binding.SyncStatus.setImageResource(R.drawable.delete_all)
                binding.SyncStatus.imageTintList = ColorStateList.valueOf(0xFFF1C45B.toInt())
                binding.SyncStatus.contentDescription = getString(R.string.delete_all)
                binding.SyncStatus.setOnClickListener {
                    activeScreen()?.let { screen ->
                        (screen as? com.omgodse.notally.fragments.Deleted)?.deleteAllNotes()
                    }
                }
            }
            R.id.Notes -> {
                val complete = model.allNotes.value?.let {
                    RoseDriveState.allSynced(this, it)
                } ?: false
                binding.SyncStatus.setImageResource(
                    if (complete) R.drawable.refresh_cw else R.drawable.refresh_cw_off)
                binding.SyncStatus.imageTintList = ColorStateList.valueOf(
                    if (complete) 0xFFF1C45B.toInt() else 0xFF98989D.toInt())
                binding.SyncStatus.contentDescription = getString(
                    if (complete) R.string.sync_up_to_date else R.string.sync_pending)
                binding.SyncStatus.setOnClickListener { requestManualBackup() }
            }
        }
        binding.BottomPill.visibility =
            if (destination.id == R.id.Notes && !model.actionMode.isEnabled()) View.VISIBLE else View.GONE
        binding.FoldersBar.visibility =
            if (destination.id == R.id.Notes && !model.actionMode.isEnabled()) View.VISIBLE else View.GONE

        val inputManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (destination.id == R.id.Search) {
            binding.EnterSearchKeyword.visibility = View.VISIBLE
            binding.EnterSearchKeyword.requestFocus()
            inputManager.showSoftInput(binding.EnterSearchKeyword, InputMethodManager.SHOW_IMPLICIT)
        } else {
            binding.EnterSearchKeyword.visibility = View.GONE
            inputManager.hideSoftInputFromWindow(binding.EnterSearchKeyword.windowToken, 0)
        }
    }

    private fun showNavigationSheet() {
        val dialog = BottomSheetDialog(this)
        val dark = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1C1E.toInt())
        val surface = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0xFFF5F5F7.toInt())
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(30))
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadii = floatArrayOf(dp(28).toFloat(),dp(28).toFloat(),dp(28).toFloat(),dp(28).toFloat(),0f,0f,0f,0f)
            }
        }
        val header = TextView(this).apply {
            text = getString(R.string.notes)
            setTextColor(dark)
            textSize = 23f
            typeface = androidx.core.content.res.ResourcesCompat.getFont(this@MainActivity, R.font.notas_sans)
            setPadding(dp(9),0,0,dp(16))
        }
        container.addView(header)
        val items = listOf(
            Triple(R.string.notes,R.drawable.home,R.id.Notes),
            Triple(R.string.labels,R.drawable.label,R.id.Labels),
            Triple(R.string.archived,R.drawable.archive,R.id.Archived),
            Triple(R.string.deleted,R.drawable.delete,R.id.Deleted),
            Triple(R.string.settings,R.drawable.settings,R.id.Settings)
        )
        for ((textRes, drawableRes, destinationId) in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                background = android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(0x30F1C45B), null, null
                )
            }
            val icon = ImageView(this).apply {
                setImageResource(drawableRes)
                imageTintList = ColorStateList.valueOf(0xFFF1C45B.toInt())
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(23),dp(23)).apply {
                marginStart = dp(12); marginEnd = dp(17)
            })
            val label = TextView(this).apply {
                setText(textRes)
                setTextColor(dark)
                textSize = 16f
                typeface = androidx.core.content.res.ResourcesCompat.getFont(this@MainActivity, R.font.notas_sans)
            }
            row.addView(label)
            row.setOnClickListener {
                dialog.dismiss()
                if (navController.currentDestination?.id != destinationId) navigateWithAnimation(destinationId)
            }
            container.addView(row)
        }
        dialog.setContentView(container)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
                backgroundTintList = null
                background = android.graphics.drawable.ColorDrawable(AndroidColor.TRANSPARENT)
                clipToOutline = false
            }
            dialog.window?.navigationBarColor = surface
        }
        dialog.show()
    }

    private fun navigateWithAnimation(id: Int) {
        val options = navOptions {
            launchSingleTop = true
            anim {
                exit = androidx.navigation.ui.R.anim.nav_default_exit_anim
                enter = androidx.navigation.ui.R.anim.nav_default_enter_anim
                popExit = androidx.navigation.ui.R.anim.nav_default_pop_exit_anim
                popEnter = androidx.navigation.ui.R.anim.nav_default_pop_enter_anim
            }
            popUpTo(navController.graph.startDestination) { inclusive = false }
        }
        navController.navigate(id, null, options)
    }


    private fun setupSearch() {
        binding.EnterSearchKeyword.setText(model.searchQuery.value.first)
        binding.EnterSearchKeyword.doAfterTextChanged { text ->
            model.updateSearchKeyword(requireNotNull(text).trim().toString())
        }
    }

    companion object {
        const val EXTRA_LINKED_FOLDER = "rose.link.open_folder"
        private const val REQUEST_EXPORT_FILE = 10
    }
}