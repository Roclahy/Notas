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
import java.util.concurrent.TimeUnit

/**
 * Notas: un solo trabajo activo y como máximo una petición pendiente.
 * El Worker lee el estado más reciente de Room; no se pierde ninguna edición.
 *
 * Las copias solicitadas al guardar no usan CONNECTED como constraint:
 * HyperOS/VPN puede no detectar correctamente una red funcional y dejar
 * una copia esperando indefinidamente. Se intenta HTTPS directamente y
 * WorkManager reintenta con backoff ante fallos temporales.
 * El respaldo diario SÍ espera una red validada para ahorrar energía.
 */
object BackupScheduler {
    private const val LEGACY_JOB = "Notas encrypted daily backup"
    private const val DAILY_JOB = "Rose Google Drive encrypted daily backup"
    private const val OLD_FIRST = "Rose Google Drive encrypted first backup"
    private const val OLD_CHANGED = "Rose Google Drive encrypted changed notes"
    private const val OLD_QUEUE = "Rose Google Drive encrypted uploads"
    private const val CLOUD_QUEUE = "Notas Drive encrypted uploads v2"

    private fun ready(app: Application): Boolean =
        RoseDriveState.enabled(app) && BackupSecrets.read(app) != null

    private fun request(delaySeconds: Long, manual: Boolean) =
        OneTimeWorkRequest.Builder(RoseDriveWorker::class.java)
            .setInputData(workDataOf("manual" to manual))
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

    fun schedule(app: Application, selectedUri: Uri? = null, refresh: Boolean = false) {
        val wm = WorkManager.getInstance(app)
        wm.cancelUniqueWork("Auto Backup")
        wm.cancelUniqueWork(LEGACY_JOB)
        wm.cancelUniqueWork(OLD_FIRST)
        wm.cancelUniqueWork(OLD_CHANGED)
        wm.cancelUniqueWork(OLD_QUEUE) // limpiar cola obsoleta que podía quedar atascada
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
        // UPDATE mantiene el calendario, pero corrige las constraints previas.
        wm.enqueueUniquePeriodicWork(DAILY_JOB, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        if (refresh) force(app)
    }

    /**
     * Cada cambio reinicia una sola ventana breve de 3 s. Una subida
     * ya iniciada no se cancela: queda marcada como sucia y el propio
     * Worker añade como máximo un trabajo posterior.
     */
    @Synchronized
    fun changed(app: Application, urgent: Boolean = false) {
        if (!ready(app)) return
        if (RoseDriveState.isUploading(app)) {
            RoseDriveState.markDirty(app)
            return
        }
        RoseDriveState.markQueued(app)
        WorkManager.getInstance(app).enqueueUniqueWork(
            CLOUD_QUEUE, ExistingWorkPolicy.REPLACE, request(if (urgent) 0 else 3, false))
    }

    /** Una petición manual no queda retenida detrás de un retry antiguo. */
    @Synchronized
    fun force(app: Application): Boolean {
        if (!ready(app)) return false
        if (RoseDriveState.isUploading(app)) {
            RoseDriveState.markDirty(app)
            return true
        }
        RoseDriveState.markQueued(app)
        WorkManager.getInstance(app).enqueueUniqueWork(
            CLOUD_QUEUE, ExistingWorkPolicy.REPLACE, request(0, true))
        return true
    }

    /**
     * Invocado al FINAL del Worker, antes de regresar SUCCESS: agrega un
     * sucesor a la ejecución presente sin cancelarse a sí mismo.
     */
    fun followUp(app: Application) {
        if (!ready(app)) return
        RoseDriveState.markQueued(app)
        WorkManager.getInstance(app).enqueueUniqueWork(
            CLOUD_QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request(0, false))
    }

    fun disable(app: Application) {
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
