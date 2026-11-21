package com.omgodse.notally.widget

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.omgodse.notally.R
import com.omgodse.notally.activities.MakeList
import com.omgodse.notally.activities.TakeNote
import com.omgodse.notally.miscellaneous.Constants
import com.omgodse.notally.preferences.Preferences
import com.omgodse.notally.room.NotallyDatabase
import com.omgodse.notally.room.Type
import com.omgodse.notally.security.PinSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Stickie 2x2. Muestra una vista previa solo de notas sin protección. */
class WidgetProvider : AppWidgetProvider() {
    override fun onDeleted(context: Context, ids: IntArray) {
        val prefs = Preferences.getInstance(context.applicationContext as Application)
        ids.forEach(prefs::deleteWidget)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ids.forEach { renderWidget(context, manager, it) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                renderWidget(context.applicationContext, manager, id)
            }
        }

        private fun renderWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val app = context.applicationContext as Application
            val prefs = Preferences.getInstance(app)
            val layout = when (prefs.getWidgetTheme(id)) {
                "light" -> R.layout.sticky_widget_light
                "dark" -> R.layout.sticky_widget_dark
                "amoled" -> R.layout.sticky_widget_amoled
                else -> R.layout.sticky_widget
            }
            val views = RemoteViews(context.packageName, layout)
            val noteId = prefs.getWidgetData(id)
            val note = if (noteId > 0) {
                NotallyDatabase.getDatabase(app).getBaseNoteDao().get(noteId)
            } else null
            val protectedFolder = note?.notebookId?.takeIf { it > 0L }?.let {
                NotallyDatabase.getDatabase(app).getNotebookFolderDao().getNow(it)
                    ?.passwordHash?.isNotBlank() == true
            } ?: false
            val locked = PinSecurity.appEnabled(context) ||
                (noteId > 0L && PinSecurity.noteLocked(context, noteId)) || protectedFolder

            val heading = when {
                locked -> context.getString(R.string.sticky_locked)
                note?.title?.isNotBlank() == true -> note.title
                else -> context.getString(R.string.internal_app_name)
            }
            val preview = when {
                locked -> context.getString(R.string.sticky_locked_hint)
                note == null -> context.getString(R.string.sticky_hint)
                note.type == Type.LIST -> note.items.joinToString("\n") { it.body }
                else -> note.body
            }.take(400).ifBlank { context.getString(R.string.sticky_hint) }
            views.setTextViewText(R.id.StickyTitle, heading)
            views.setTextViewText(R.id.StickyHint, preview)

            val target = if (note?.type == Type.LIST) MakeList::class.java
                else TakeNote::class.java
            val open = Intent(context, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (note != null) open.putExtra(Constants.SelectedBaseNote, note.id)
            val openIntent = PendingIntent.getActivity(context, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.StickyCard, openIntent)

            val add = Intent(context, TakeNote::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val addIntent = PendingIntent.getActivity(context, id xor 0x2B2B, add,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.StickyAction, addIntent)
            manager.updateAppWidget(id, views)
        }

        fun sendBroadcast(app: Application, ids: LongArray) {
            val manager = AppWidgetManager.getInstance(app)
            val component = ComponentName(app, WidgetProvider::class.java)
            val prefs = Preferences.getInstance(app)
            val relevant = prefs.getUpdatableWidgets(ids).map { it.first }.toSet()
            manager.getAppWidgetIds(component).forEach {
                if (ids.isEmpty() || it in relevant || PinSecurity.appEnabled(app))
                    updateWidget(app, manager, it)
            }
        }
        fun sendBroadcast(app: Application, id: Long) = sendBroadcast(app, longArrayOf(id))
    }
}
