package com.omgodse.notally.activities

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
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

    override fun onBackPressed() {
        if (model.actionMode.enabled.value) {
            model.actionMode.close(true)
        } else super.onBackPressed()
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
        if (savedInstanceState == null) RoseSplashView.show(this)
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
            startActivity(Intent(this, TakeNote::class.java))
        }
        binding.MakeList.setOnClickListener {
            startActivity(Intent(this, MakeList::class.java))
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
        button.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90L).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.animate().scaleX(1f).scaleY(1f).setDuration(135L).start()
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        target.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }
            }
            false // Conserva accesibilidad y eventos normales de clic.
        }
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
        binding.SectionTitle.setOnClickListener {
            val top = setOf(R.id.Notes, R.id.Labels, R.id.Deleted, R.id.Archived, R.id.Settings)
            if (navController.currentDestination?.id in top) showNavigationSheet()
            else onSupportNavigateUp()
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
        binding.SectionTitle.text = if (destination.id == R.id.Notes) getString(R.string.app_name) else (destination.label ?: getString(R.string.notes))
        binding.SectionTitle.visibility = if (destination.id == R.id.Search) View.GONE else View.VISIBLE
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
        private const val REQUEST_EXPORT_FILE = 10
    }
}