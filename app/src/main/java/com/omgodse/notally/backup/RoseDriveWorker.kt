package com.omgodse.notally.backup

import android.app.Application
import android.content.Context
import com.omgodse.notally.room.NotallyDatabase
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.common.api.Scope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Notas funciona completamente offline para notas y tareas.
 * Este trabajador solo se ejecuta si el usuario autorizó Google Drive.
 */
class RoseDriveWorker(context: Context, params: WorkerParameters) : Worker(context,params) {
    companion object {
        private val lock = java.util.concurrent.locks.ReentrantLock()
    }
    override fun doWork(): Result {
        if (!lock.tryLock()) return Result.retry()
        val app = applicationContext as Application
        var file: File? = null
        var passphrase: CharArray? = null
        return try {
            if (!RoseDriveState.enabled(app)) return Result.success()
            passphrase = BackupSecrets.read(app) ?: return Result.failure()

            val request = AuthorizationRequest.builder().setRequestedScopes(
                listOf(Scope("https://www.googleapis.com/auth/drive.file"))
            ).build()
            val authorization = Tasks.await(
                Identity.getAuthorizationClient(app).authorize(request), 45L, TimeUnit.SECONDS
            )
            if (authorization.hasResolution() || authorization.accessToken.isNullOrEmpty()) {
                RoseDriveState.markNeedsAuthorization(app)
                return Result.failure()
            }
            // Captura conservadora anterior a crear la copia.
            // Nunca se marcan datos que se modifiquen tras esta captura.
            val recorded = RoseDriveState.snapshot(
                NotallyDatabase.getDatabase(app).getBaseNoteDao().getAllForSync())
            val settingsHash = RoseDriveState.settingsFingerprint(app)
            file = File.createTempFile("notas_backup_", ".rnotas", app.cacheDir)
            file.outputStream().use { output ->
                EncryptedBackups.write(app, output, requireNotNull(passphrase))
            }
            val name = "Notas-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".rnotas"
            RoseDriveApi.backup(requireNotNull(authorization.accessToken), requireNotNull(file), name)
            // Únicamente después de obtener éxito HTTP en Google Drive.
            RoseDriveState.markUploaded(app, recorded, settingsHash)
            Result.success()
        } catch (_: Exception) {
            Result.retry() // WorkManager reintenta al volver Internet.
        } finally {
            file?.delete()
            passphrase?.fill('\u0000')
            lock.unlock()
        }
    }
}
