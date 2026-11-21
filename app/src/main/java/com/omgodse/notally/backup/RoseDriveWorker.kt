package com.omgodse.notally.backup

import android.app.Application
import android.content.Context
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
 * rNotas funciona completamente offline para notas y tareas.
 * Este trabajador solo se ejecuta si el usuario autorizó Google Drive.
 */
class RoseDriveWorker(context: Context, params: WorkerParameters) : Worker(context,params) {
    override fun doWork(): Result {
        val app = applicationContext as Application
        if (!RoseDriveState.enabled(app)) return Result.success()
        val passphrase = BackupSecrets.read(app) ?: return Result.failure()
        var file: File? = null
        try {
            val request = AuthorizationRequest.builder().setRequestedScopes(
                listOf(Scope("https://www.googleapis.com/auth/drive.file"))
            ).build()
            val authorization = Tasks.await(
                Identity.getAuthorizationClient(app).authorize(request),45L,TimeUnit.SECONDS
            )
            if (authorization.hasResolution() || authorization.accessToken.isNullOrEmpty()) {
                RoseDriveState.markNeedsAuthorization(app)
                return Result.failure() // No abrir consentimiento desde un Worker.
            }
            file = File.createTempFile("rose_backup_",".rnotas",app.cacheDir)
            file.outputStream().use { output -> EncryptedBackups.write(app,output,passphrase) }
            val name = "rNotas-" +
                SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(Date()) + ".rnotas"
            RoseDriveApi.backup(requireNotNull(authorization.accessToken),file,name)
            RoseDriveState.markUploaded(app)
            return Result.success()
        } catch (_: Exception) {
            return Result.retry() // WorkManager reintenta al regresar Internet.
        } finally {
            file?.delete() // Solo se almacena temporalmente el archivo ya cifrado.
            passphrase.fill('\u0000')
        }
    }
}
