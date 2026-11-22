package com.omgodse.notally.backup

import android.app.Application
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Google Drive: guardado inmediato en el proceso y WorkManager como respaldo duradero.
 *
 * WorkManager no garantiza la ejecución inmediata. Los cambios locales se agrupan
 * durante 3 s en un hilo de E/S propio; la petición manual empieza sin demora.
 * Un trabajo persistente independiente recupera la operación si Android cierra
 * el proceso o falla la transferencia. Ninguna nueva edición cancela una subida.
 */
object BackupScheduler {
    private const val LEGACY_JOB = "Notas encrypted daily backup"
    private const val DAILY_JOB = "Rose Google Drive encrypted daily backup"
    private const val OLD_FIRST = "Rose Google Drive encrypted first backup"
    private const val OLD_CHANGED = "Rose Google Drive encrypted changed notes"
    private const val OLD_QUEUE = "Rose Google Drive encrypted uploads"
    private const val CLOUD_QUEUE = "Notas Drive encrypted uploads v2"

    private const val EDIT_DEBOUNCE_SECONDS = 3L
    private const val FALLBACK_DELAY_SECONDS = 45L

    private val immediate = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "Notas-Drive-immediate").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
    }
    private var pendingImmediate: ScheduledFuture<*>? = null

    private fun ready(app: Application): Boolean =
        RoseDriveState.enabled(app) && BackupSecrets.read(app) != null

    private fun request(delaySeconds: Long) =
        OneTimeWorkRequest.Builder(RoseDriveWorker::class.java)
            .setInputData(workDataOf("manual" to false))
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

    @Synchronized
    private fun queueImmediate(app: Application, delaySeconds: Long, manual: Boolean, includeImages: Boolean = manual || BackupMediaPolicy.includeAutomaticImages(app)) {
        pendingImmediate?.cancel(false) // solo una petición pendiente; nunca interrumpe la que corre
        pendingImmediate = immediate.schedule({
            if (ready(app)) {
                // Worker y subida inmediata comparten el mismo candado y la misma
                // confirmación de Drive. El fallback ya está programado si falla.
                RoseDriveWorker.perform(app, manual, includeImages)
            }
        }, delaySeconds, TimeUnit.SECONDS)
    }

    /** Persistir la intención de respaldo sin reemplazar un Worker en ejecución. */
    private fun queueFallback(app: Application) {
        WorkManager.getInstance(app).enqueueUniqueWork(
            CLOUD_QUEUE, ExistingWorkPolicy.KEEP, request(FALLBACK_DELAY_SECONDS))
    }

    fun schedule(app: Application, selectedUri: Uri? = null, refresh: Boolean = false) {
        val wm = WorkManager.getInstance(app)
        wm.cancelUniqueWork("Auto Backup")
        wm.cancelUniqueWork(LEGACY_JOB)
        wm.cancelUniqueWork(OLD_FIRST)
        wm.cancelUniqueWork(OLD_CHANGED)
        wm.cancelUniqueWork(OLD_QUEUE)
        RoseDriveState.resetInterruptedUpload(app)
        if (!ready(app)) {
            wm.cancelUniqueWork(DAILY_JOB)
            wm.cancelUniqueWork(CLOUD_QUEUE)
            return
        }
        val periodic = PeriodicWorkRequest.Builder(
            RoseDriveWorker::class.java, 24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(DAILY_JOB, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        if (refresh) force(app)
    }

    /** Tras cambios en Room, enviar en 3 s sin esperar al JobScheduler de Android. */
    @Synchronized
    fun changed(app: Application, urgent: Boolean = false) {
        if (!ready(app)) return
        if (RoseDriveState.isUploading(app)) {
            RoseDriveState.markDirty(app)
            queueFallback(app)
            return
        }
        RoseDriveState.markQueued(app)
        queueFallback(app)
        queueImmediate(app, if (urgent) 0 else EDIT_DEBOUNCE_SECONDS, false)
    }

    /** El toque manual inicia directamente en E/S, sin retraso de WorkManager. */
    @Synchronized
    fun force(app: Application, includeImages: Boolean = true): Boolean {
        if (!ready(app)) return false
        if (RoseDriveState.isUploading(app)) {
            RoseDriveState.markDirty(app)
            queueFallback(app)
            return true
        }
        RoseDriveState.markQueued(app)
        queueFallback(app)
        queueImmediate(app, 0, true, includeImages)
        return true
    }

    /**
     * Si cambian datos durante una transferencia, ejecutar una nueva instantánea
     * al liberar el candado. APPEND_OR_REPLACE evita cancelar el Worker actual.
     */
    @Synchronized
    fun followUp(app: Application) {
        if (!ready(app)) return
        RoseDriveState.markQueued(app)
        queueImmediate(app, 1, false)
        WorkManager.getInstance(app).enqueueUniqueWork(
            CLOUD_QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(FALLBACK_DELAY_SECONDS))
    }

    @Synchronized
    fun disable(app: Application) {
        pendingImmediate?.cancel(false)
        pendingImmediate = null
        val wm = WorkManager.getInstance(app)
        wm.cancelUniqueWork(CLOUD_QUEUE)
        wm.cancelUniqueWork(DAILY_JOB)
        wm.cancelUniqueWork(OLD_QUEUE)
        wm.cancelUniqueWork(OLD_FIRST)
        wm.cancelUniqueWork(OLD_CHANGED)
        wm.cancelUniqueWork(LEGACY_JOB)
        wm.cancelUniqueWork("Auto Backup")
        RoseDriveState.resetInterruptedUpload(app)
    }
}
