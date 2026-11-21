package com.omgodse.notally.activities

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.omgodse.notally.R
import com.omgodse.notally.preferences.Preferences
import com.omgodse.notally.room.NotallyDatabase
import com.omgodse.notally.widget.WidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Configura una nota o acceso rápido y el aspecto Stickie, sin saltar el bloqueo de la app. */
class ConfigureWidget : NotasBaseActivity() {
    private var configurationStarted = false
    private val widgetId by lazy {
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED, Intent()
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) finish()
    }

    override fun onAppAccessGranted() {
        if (configurationStarted || isFinishing) return
        configurationStarted = true
        lifecycleScope.launch {
            val notes = withContext(Dispatchers.IO) {
                NotallyDatabase.getDatabase(application).getBaseNoteDao().getAllNotes()
            }
            val options = arrayOf(getString(R.string.sticky_new_note)) +
                notes.map { note ->
                    note.title.takeIf { it.isNotBlank() }
                        ?: note.body.take(35).takeIf { it.isNotBlank() }
                        ?: getString(R.string.empty_note)
                }
            MaterialAlertDialogBuilder(this@ConfigureWidget)
                .setTitle(R.string.sticky_choose_note)
                .setItems(options) { _, index ->
                    val selectedId = if (index == 0) 0L else notes[index - 1].id
                    showStylePicker(selectedId)
                }
                .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    private fun showStylePicker(noteId: Long) {
        val choices = arrayOf(
            getString(R.string.sticky_gold), getString(R.string.sticky_light),
            getString(R.string.sticky_dark), getString(R.string.sticky_amoled))
        val keys = arrayOf("gold", "light", "dark", "amoled")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sticky_choose_style)
            .setSingleChoiceItems(choices, 0) { dialog, index ->
                val prefs = Preferences.getInstance(application)
                prefs.updateWidget(widgetId, noteId)
                prefs.setWidgetTheme(widgetId, keys[index])
                WidgetProvider.updateWidget(this, AppWidgetManager.getInstance(this), widgetId)
                setResult(RESULT_OK, Intent()
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                dialog.dismiss()
                finish()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
