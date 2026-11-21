package com.omgodse.notally.backup

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Una cola serializada para cambios y solicitudes manuales: no se interrumpe
 * un upload en curso para atender otra edición. Las copias se compactan al
 * comprobar el último estado confirmado de Drive dentro del trabajador.
 */
object BackupScheduler {
    private const val LEGACY_JOB = "Notas encrypted daily backup"
    private const val CLOUD_JOB = "Rose Google Drive encrypted daily backup"
    private const val OLD_NOW = "Rose Google Drive encrypted first backup"
    private const val OLD_CHANGED = "Rose Google Drive encrypted changed notes"
    private const val CLOUD_QUEUE = "Rose Google Drive encrypted uploads"
    private const val DEBOUNCE_MS = 8_000L
    @Volatile private var lastQueuedAt = 0L

    private fun ready(app: Application): Boolean =
        RoseDriveState.enabled(app) && BackupSecrets.read(app) != null

    private fun internet() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun request(delaySeconds: Long, manual: Boolean) =
        OneTimeWorkRequest.Builder(RoseDriveWorker::class.java)
            .setConstraints(internet())
            .setInputData(workDataOf("manual" to manual))
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .build()

    fun schedule(app: Application, selectedUri: Uri? = null, refresh: Boolean = false) {
        val work = WorkManager.getInstance(app)
        work.cancelUniqueWork("Auto Backup")
        work.cancelUniqueWork(LEGACY_JOB)
        // Solo estos dos nombres pertenecen a la implementación anterior.
        work.cancelUniqueWork(OLD_NOW)
        work.cancelUniqueWork(OLD_CHANGED)
        if (!ready(app)) {
            work.cancelUniqueWork(CLOUD_JOB)
            work.cancelUniqueWork(CLOUD_QUEUE)
            return
        }
        val periodic = PeriodicWorkRequest.Builder(RoseDriveWorker::class.java, 24, TimeUnit.HOURS)
            .setConstraints(internet())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniquePeriodicWork(
            CLOUD_JOB, ExistingPeriodicWorkPolicy.KEEP, periodic)
        if (refresh) force(app)
    }

    /** Al guardar, agrupa ediciones cercanas y encadena sin cancelar subidas. */
    @Synchronized
    fun changed(app: Application, urgent: Boolean = false) {
        if (!ready(app)) return
        val now = SystemClock.elapsedRealtime()
        if (!urgent && lastQueuedAt > 0 && now >= lastQueuedAt &&
            now - lastQueuedAt < DEBOUNCE_MS) return
        lastQueuedAt = now
        RoseDriveState.markQueued(app)
        WorkManager.getInstance(app).enqueueUniqueWork(
            CLOUD_QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(if (urgent) 0 else 8, false))
    }

    /** El botón manual salta el debounce, pero mantiene el orden seguro. */
    fun force(app: Application): Boolean {
        if (!ready(app)) return false
        RoseDriveState.markQueued(app)
        WorkManager.getInstance(app).enqueueUniqueWork(
            CLOUD_QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request(0, true))
        return true
    }

    fun disable(app: Application) {
        val work = WorkManager.getInstance(app)
        work.cancelUniqueWork(CLOUD_QUEUE)
        work.cancelUniqueWork(CLOUD_JOB)
        work.cancelUniqueWork(OLD_NOW)
        work.cancelUniqueWork(OLD_CHANGED)
        work.cancelUniqueWork(LEGACY_JOB)
        work.cancelUniqueWork("Auto Backup")
        lastQueuedAt = 0L
    }
}
