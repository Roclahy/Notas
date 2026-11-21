package com.omgodse.notally.activities

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.omgodse.notally.R
import com.omgodse.notally.preferences.Preferences
import com.omgodse.notally.widget.WidgetProvider

/** Selector de apariencia del único widget compacto 2x2 de Notas. */
class ConfigureWidget : NotasBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val cancelled = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(RESULT_CANCELED, cancelled)
        val options = arrayOf(
            getString(R.string.sticky_gold),
            getString(R.string.sticky_light),
            getString(R.string.sticky_dark),
            getString(R.string.sticky_amoled))
        val keys = arrayOf("gold", "light", "dark", "amoled")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sticky_choose_style)
            .setSingleChoiceItems(options, 0) { dialog, selected ->
                val prefs = Preferences.getInstance(application)
                prefs.setWidgetTheme(widgetId, keys[selected])
                WidgetProvider.updateWidget(
                    this, AppWidgetManager.getInstance(this), widgetId)
                setResult(RESULT_OK, cancelled)
                dialog.dismiss()
                finish()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
