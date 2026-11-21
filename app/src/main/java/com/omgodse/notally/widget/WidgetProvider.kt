package com.omgodse.notally.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.omgodse.notally.R
import com.omgodse.notally.activities.TakeNote
import com.omgodse.notally.preferences.Preferences

/** Widget independiente de las notas almacenadas: evita filtrar contenido protegido. */
class WidgetProvider : AppWidgetProvider() {
    override fun onDeleted(context: Context, ids: IntArray) {
        val prefs = Preferences.getInstance(context.applicationContext as android.app.Application)
        ids.forEach(prefs::deleteWidget)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it) }
    }

    companion object {
        fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = Preferences.getInstance(context.applicationContext as android.app.Application)
            val style = prefs.getWidgetTheme(id)
            val background = when (style) {
                "light" -> R.drawable.sticky_widget_light
                "dark" -> R.drawable.sticky_widget_dark
                "amoled" -> R.drawable.sticky_widget_amoled
                else -> R.drawable.sticky_widget_gold
            }
            val ink = if (style == "gold" || style == "light") {
                Color.rgb(30, 28, 25)
            } else {
                Color.rgb(241, 196, 91)
            }
            val views = RemoteViews(context.packageName, R.layout.sticky_widget)
            views.setInt(R.id.StickyCard, "setBackgroundResource", background)
            views.setTextColor(R.id.StickyTitle, ink)
            views.setTextColor(R.id.StickyHint, ink)
            views.setTextColor(R.id.StickyAction, ink)
            views.setInt(R.id.StickyIcon, "setColorFilter", ink)
            val launch = Intent(context, TakeNote::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val click = PendingIntent.getActivity(context, id, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.StickyCard, click)
            manager.updateAppWidget(id, views)
        }

        /** Se conserva para que los editores existentes no necesiten cambios. */
        fun sendBroadcast(app: android.app.Application, ids: LongArray) {
            val context: Context = app
            val manager = AppWidgetManager.getInstance(context)
            val component = android.content.ComponentName(context, WidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        fun sendBroadcast(app: android.app.Application, id: Long) =
            sendBroadcast(app, longArrayOf(id))
    }
}
