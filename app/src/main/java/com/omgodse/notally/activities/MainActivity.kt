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
import android.view.Gravity
import android.view.ViewGroup
import android.text.InputType
import android.os.Handler
import android.os.Looper
import com.omgodse.notally.security.NotebookPassword
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
import com.omgodse.notally.view.LiquidGlass
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
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var selectedProtectedNotebook = false

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
        setupGlassChrome()
    }

    /** Glass es una capa sobre Claro, Oscuro y AMOLED; nunca cambia el fondo. */
    private fun setupGlassChrome() {
        if (!LiquidGlass.enabled(this)) return
        // AGSL refracts only the visible notes underneath the floating bar.
        // It does not restyle the drawer as a huge translucent card.
        LiquidGlass.attachBackdrop(binding.BottomPill, binding.NavHostFragment)
        binding.BottomPill.elevation = resources.displayMetrics.density * 8f
        binding.TakeNote.background = LiquidGlass.surface(
            this, LiquidGlass.Role.ACCENT)
        binding.Toolbar.setBackgroundColor(AndroidColor.TRANSPARENT)
    }

    private fun setupSyncStatus() {
        fun refresh() {
            val complete = model.allNotes.value?.let { RoseDriveState.allSynced(this, it) } ?: false
            binding.SyncStatus.setImageResource(if (complete) R.drawable.refresh_cw else R.drawable.refresh_cw_off)
            binding.SyncStatus.imageTintList = ColorStateList.valueOf(
                if (complete) 0xFFF1C45B.toInt() else 0xFF98989D.toInt())
            binding.SyncStatus.contentDescription =
                getString(if (complete) R.string.sync_up_to_date else R.string.sync_pending)
        }
        model.allNotes.observe(this) { refresh() }
        RoseDriveState.revision.observe(this) { refresh() }
        binding.SyncStatus.setOnClickListener {
            if (BackupScheduler.force(application)) {
                Toast.makeText(this, R.string.sync_queued, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.sync_not_configured, Toast.LENGTH_LONG).show()
                if (navController.currentDestination?.id != R.id.Settings) {
                    navController.navigate(R.id.Settings)
                }
            }
        }
        refresh()
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
        var folderGestureTriggered = false
        val openFolders = Runnable {
            folderGestureTriggered = true
            button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showNotebookFolders()
        }
        button.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    folderGestureTriggered = false
                    if (target === binding.TakeNote) {
                        longPressHandler.postDelayed(openFolders, 2_000L)
                    }
                    if (LiquidGlass.enabled(this)) LiquidGlass.animatePress(target, true)
                    else target.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90L).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(openFolders)
                    if (LiquidGlass.enabled(this)) LiquidGlass.animatePress(target, false)
                    else target.animate().scaleX(1f).scaleY(1f).setDuration(135L).start()
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        !folderGestureTriggered) {
                        target.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }
            }
            folderGestureTriggered
        }
        // Alternativa accesible desde el menú de secciones; el gesto
        // táctil usa exactamente 2 segundos (no el long-click de ~500 ms).
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
                background = if (LiquidGlass.enabled(this@MainActivity)) {
                    LiquidGlass.surface(this@MainActivity, LiquidGlass.Role.SHEET)
                } else GradientDrawable().apply {
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
            fun row(name: String, detail: String, isProtected: Boolean, action: () -> Unit) {
                val item = LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    minimumHeight = px(62)
                    setPadding(px(14), px(8), px(12), px(8))
                    background = android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(0x33F1C45B), null, null)
                }
                val folderIcon = ImageView(this@MainActivity).apply {
                    setImageResource(if (isProtected) R.drawable.notas_lock else R.drawable.notas_nav_folder)
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
                item.setOnClickListener { dialog.dismiss(); action() }
                container.addView(item)
            }
            row(getString(R.string.notebook_all), "", false) {
                selectNotebook(-1L, getString(R.string.notebook_all), false)
            }
            row(getString(R.string.notebook_unfiled), "", false) {
                selectNotebook(0L, getString(R.string.notebook_unfiled), false)
            }
            folders.forEach { folder ->
                row(folder.name, "", folder.passwordHash.isNotEmpty()) {
                    if (folder.passwordHash.isEmpty()) {
                        selectNotebook(folder.id, folder.name, false)
                    } else requestNotebookPassword(folder) {
                        selectNotebook(folder.id, folder.name, true)
                    }
                }
            }
            row(getString(R.string.notebook_new), "+", false) { createNotebookDialog() }
            dialog.setContentView(container)
            dialog.setOnShowListener {
                dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    ?.setBackgroundColor(AndroidColor.TRANSPARENT)
                LiquidGlass.styleDialog(dialog, this@MainActivity)
            }
            dialog.show()
        }
    }

    private fun createNotebookDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.notebook_name)
            isSingleLine = true
            setPadding(32, 16, 32, 16)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notebook_new)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_) { _, _ ->
                val name = input.text.toString().trim().take(80)
                if (name.isBlank()) return@setPositiveButton
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.notebook_protect)
                    .setNegativeButton(R.string.notebook_unfiled) { _, _ ->
                        saveNotebook(name, null)
                    }
                    .setPositiveButton(R.string.notebook_protect) { _, _ ->
                        val passwordInput = EditText(this).apply {
                            hint = getString(R.string.notebook_password)
                            inputType = InputType.TYPE_CLASS_TEXT or
                                InputType.TYPE_TEXT_VARIATION_PASSWORD
                            isSingleLine = true
                        }
                        val passwordDialog = MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.notebook_protect)
                            .setView(passwordInput)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.save, null)
                            .create()
                        passwordDialog.setOnShowListener {
                            passwordDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                                .setOnClickListener {
                                    val secret = passwordInput.text.toString()
                                    if (secret.length < 12) {
                                        passwordInput.error = getString(R.string.notebook_password)
                                    } else {
                                        passwordDialog.dismiss()
                                        saveNotebook(name, secret)
                                    }
                                }
                        }
                        passwordDialog.show()
                    }.show()
            }.show()
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

    private fun requestNotebookPassword(folder: NotebookFolder, onVerified: () -> Unit) {
        val entry = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.notebook_password)
            isSingleLine = true
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(folder.name)
            .setView(entry)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener {
                    val secret = entry.text.toString().toCharArray()
                    lifecycleScope.launch {
                        val valid = withContext(Dispatchers.Default) {
                            try { NotebookPassword.verify(secret, folder.passwordHash) }
                            finally { secret.fill('\u0000') }
                        }
                        if (valid) {
                            entry.text?.clear()
                            dialog.dismiss()
                            onVerified()
                        } else entry.error = getString(R.string.notebook_wrong_password)
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
            } else {
                binding.Toolbar.visibility = View.VISIBLE
                binding.ActionMode.visibility = View.GONE
                binding.DrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                binding.BottomPill.visibility = if (navController.currentDestination?.id == R.id.Notes) View.VISIBLE else View.GONE
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
        binding.SyncStatus.visibility = if (destination.id == R.id.Notes) View.VISIBLE else View.GONE
        binding.BottomPill.visibility =
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
            background = if (LiquidGlass.enabled(this@MainActivity)) {
                LiquidGlass.surface(this@MainActivity, LiquidGlass.Role.SHEET)
            } else GradientDrawable().apply {
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
        val folders = TextView(this).apply {
            setText(R.string.notebook_folders)
            textSize = 17f
            setTextColor(dark)
            setPadding(dp(24), dp(18), dp(24), dp(18))
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x30F1C45B), null, null)
            setOnClickListener {
                dialog.dismiss()
                showNotebookFolders()
            }
        }
        container.addView(folders)
        dialog.setContentView(container)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
                backgroundTintList = null
                background = android.graphics.drawable.ColorDrawable(AndroidColor.TRANSPARENT)
                clipToOutline = false
            }
            dialog.window?.navigationBarColor =
                if (LiquidGlass.enabled(this)) AndroidColor.TRANSPARENT else surface
            LiquidGlass.styleDialog(dialog, this)
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
        private const val REQUEST_EXPORT_FILE = 10
    }
}