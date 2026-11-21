package com.omgodse.notally.backup

import android.app.Application
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.omgodse.notally.AutoBackupWorker
import com.omgodse.notally.preferences.AutoBackup
import com.omgodse.notally.preferences.Preferences
import java.util.concurrent.TimeUnit

object BackupScheduler {
    private const val JOB = "Notas encrypted daily backup"

    fun schedule(app: Application, selectedUri: Uri? = null, refresh: Boolean = false) {
        val manager = WorkManager.getInstance(app)
        // Desactiva el trabajador antiguo que generaba ZIP sin cifrar.
        manager.cancelUniqueWork("Auto Backup")
        val uri = selectedUri ?: Preferences.getInstance(app).autoBackup.value
            .takeUnless { it == AutoBackup.emptyPath }?.let(Uri::parse)
        if (uri == null || BackupSecrets.read(app) == null) {
            manager.cancelUniqueWork(JOB)
            return
        }
        val isCloud = uri.authority.orEmpty().contains("com.google.android.apps.docs",ignoreCase = true)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (isCloud) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
        val request = PeriodicWorkRequest.Builder(AutoBackupWorker::class.java, 24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(JOB,
            if (refresh) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.KEEP,
            request)
    }

    fun disable(app: Application) {
        WorkManager.getInstance(app).cancelUniqueWork(JOB)
    }
}
