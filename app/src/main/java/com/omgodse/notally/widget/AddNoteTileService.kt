package com.omgodse.notally.widget

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.omgodse.notally.activities.TakeNote

/** Tile del sistema que abre el editor; respeta el bloqueo de Notas. */
class AddNoteTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val addNote = Intent(this, TakeNote::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(this, 1403, addNote,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(addNote)
        }
    }
}
