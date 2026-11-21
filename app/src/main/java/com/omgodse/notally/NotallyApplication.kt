package com.omgodse.notally

import android.app.Application
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.app.Activity
import android.os.Bundle
import com.omgodse.notally.security.PinSecurity
import com.omgodse.notally.security.SecureMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatDelegate
import com.omgodse.notally.backup.BackupScheduler
import com.omgodse.notally.backup.RoseDriveState
import com.omgodse.notally.room.NotallyDatabase
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.omgodse.notally.preferences.Preferences
import com.omgodse.notally.preferences.Theme
import java.util.concurrent.TimeUnit

class NotallyApplication : Application() {
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate() {
        super.onCreate()
        // Elimina archivos ZIP temporales de restauraciones interrumpidas.
        java.io.File(cacheDir,"encrypted_restore").listFiles()?.forEach { it.delete() }
        // Los temporales legibles de la sesión anterior nunca se conservan.
        SecureMedia.clearReadableCache(this)
        // Restos de una exportación portable previa interrumpida.
        cacheDir.listFiles()?.filter {
            (it.name.startsWith("notas_portable_") && it.name.endsWith(".db")) ||
                (it.name.startsWith("notas_backup_") && it.name.endsWith(".rnotas")) ||
                (it.name.startsWith("drive_recovery_") && it.name.endsWith(".rnotas"))
        }?.forEach { it.delete() }
        CoroutineScope(Dispatchers.IO).launch {
            // Migración idempotente: solo borrar el original externo cuando
            // la versión interna cifrada esté escrita y validada.
            SecureMedia.recoverInterruptedWrites(this@NotallyApplication)
            SecureMedia.migrateLegacy(this@NotallyApplication)
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var visibleActivities = 0
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) { visibleActivities++ }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                visibleActivities = (visibleActivities - 1).coerceAtLeast(0)
                if (visibleActivities == 0) PinSecurity.appUnlocked = false
            }
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        val preferences = Preferences.getInstance(this)
        preferences.theme.observeForever { theme ->
            when (theme) {
                Theme.dark, Theme.amoled, Theme.glass -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                Theme.light -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                Theme.followSystem -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }

        BackupScheduler.schedule(this)

        PinSecurity.noteChanges.observeForever {
            RoseDriveState.invalidate()
            if (RoseDriveState.enabled(this)) BackupScheduler.changed(this)
        }

        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key in RoseDriveState.trackedSettings) {
                RoseDriveState.invalidate()
                if (RoseDriveState.enabled(this)) BackupScheduler.changed(this)
            }
        }
        settingsListener = prefListener // Mantiene viva la referencia del listener.
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(prefListener)

        // Los cambios en carpetas (incluyendo el hash de su contraseña)
        // deben respaldarse aunque no se edite ninguna nota.
        NotallyDatabase.getDatabase(this).getNotebookFolderDao().getAll()
            .observeForever {
                RoseDriveState.invalidate()
                if (RoseDriveState.enabled(this)) BackupScheduler.changed(this)
            }

        // Centraliza cambios de notas, tareas, fijados, eliminaciones y medios.
        // El estado solo pasa a sincronizado después de que Drive acepte
        // una copia cifrada completa, no al ejecutar esta observación.
        var observed: Map<Long, String>? = null
        NotallyDatabase.getDatabase(this).getBaseNoteDao().getAll().observeForever { notes ->
            val current = RoseDriveState.snapshot(notes)
            if (current != observed) {
                observed = current
                RoseDriveState.invalidate()
                if (RoseDriveState.enabled(this) && !RoseDriveState.allSynced(this, notes)) {
                    BackupScheduler.changed(this)
                }
            }
        }
    }
}