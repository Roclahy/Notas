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

object BackupScheduler {
    private const val LEGACY_JOB = "Notas encrypted daily backup"
    private const val CLOUD_JOB = "Rose Google Drive encrypted daily backup"
    private const val CLOUD_NOW = "Rose Google Drive encrypted first backup"

    fun schedule(app: Application, selectedUri: Uri? = null, refresh: Boolean = false) {
        val work = WorkManager.getInstance(app)
        // No programar la antigua copia automática en almacenamiento interno.
        work.cancelUniqueWork("Auto Backup")
        work.cancelUniqueWork(LEGACY_JOB)
        if (!RoseDriveState.enabled(app) || BackupSecrets.read(app) == null) {
            work.cancelUniqueWork(CLOUD_JOB)
            return
        }
        val constraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = PeriodicWorkRequest.Builder(RoseDriveWorker::class.java,24,TimeUnit.HOURS)
            .setConstraints(constraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(CLOUD_JOB,
            if (refresh) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.KEEP,
            periodic)
        if (refresh) {
            val first = OneTimeWorkRequest.Builder(RoseDriveWorker::class.java)
                .setConstraints(constraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.MINUTES)
                .build()
            work.enqueueUniqueWork(CLOUD_NOW,ExistingWorkPolicy.REPLACE,first)
        }
    }

    fun disable(app: Application) {
        val work=WorkManager.getInstance(app)
        work.cancelUniqueWork(CLOUD_NOW)
        work.cancelUniqueWork(CLOUD_JOB)
        work.cancelUniqueWork(LEGACY_JOB)
        work.cancelUniqueWork("Auto Backup")
    }
}
