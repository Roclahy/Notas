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
import java.util.concurrent.TimeUnit

/**
 * Diario + cada modificación de Room (agrupada 30 s) + forzado manual.
 * WorkManager no garantiza hora exacta; respeta conexión y ahorro de energía.
 */
object BackupScheduler {
    private const val LEGACY_JOB = "Notas encrypted daily backup"
    private const val CLOUD_JOB = "Rose Google Drive encrypted daily backup"
    private const val CLOUD_NOW = "Rose Google Drive encrypted first backup"
    private const val CLOUD_CHANGED = "Rose Google Drive encrypted changed notes"

    private fun ready(app: Application): Boolean =
        RoseDriveState.enabled(app) && BackupSecrets.read(app) != null

    private fun internet(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(app: Application, selectedUri: Uri? = null, refresh: Boolean = false) {
        val work = WorkManager.getInstance(app)
        work.cancelUniqueWork("Auto Backup")
        work.cancelUniqueWork(LEGACY_JOB)
        if (!ready(app)) {
            work.cancelUniqueWork(CLOUD_JOB)
            work.cancelUniqueWork(CLOUD_NOW)
            work.cancelUniqueWork(CLOUD_CHANGED)
            return
        }
        val periodic = PeriodicWorkRequest.Builder(RoseDriveWorker::class.java, 24, TimeUnit.HOURS)
            .setConstraints(internet())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(CLOUD_JOB,
            if (refresh) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.KEEP,
            periodic)
        if (refresh) force(app)
    }

    /** Se llama tras cambios locales, sin interrumpir el respaldo diario. */
    fun changed(app: Application) {
        if (!ready(app)) return
        val task = OneTimeWorkRequest.Builder(RoseDriveWorker::class.java)
            .setConstraints(internet())
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            CLOUD_CHANGED, ExistingWorkPolicy.REPLACE, task)
    }

    /** No cancela el respaldo periódico; solicita una copia inmediata cuando haya red. */
    fun force(app: Application): Boolean {
        if (!ready(app)) return false
        val task = OneTimeWorkRequest.Builder(RoseDriveWorker::class.java)
            .setConstraints(internet())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            CLOUD_NOW, ExistingWorkPolicy.REPLACE, task)
        return true
    }

    fun disable(app: Application) {
        val work = WorkManager.getInstance(app)
        work.cancelUniqueWork(CLOUD_NOW)
        work.cancelUniqueWork(CLOUD_JOB)
        work.cancelUniqueWork(CLOUD_CHANGED)
        work.cancelUniqueWork(LEGACY_JOB)
        work.cancelUniqueWork("Auto Backup")
    }
}
