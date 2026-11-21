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
        // El trabajo periódico puede coincidir con la cola por cambios.
        // No bloquear el hilo: la otra subida confirmará o reintentará.
        if (!lock.tryLock()) return Result.retry()
        val app = applicationContext as Application
        var file: File? = null
        var passphrase: CharArray? = null
        return try {
            if (!RoseDriveState.enabled(app)) return Result.success()
            passphrase = BackupSecrets.read(app) ?: run {
                RoseDriveState.markFailed(app, "Contraseña local inaccesible")
                return Result.failure()
            }
            RoseDriveState.markStarted(app)

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
            val dao = NotallyDatabase.getDatabase(app).getBaseNoteDao()
            val notesBefore = dao.getAllForSync()
            if (!inputData.getBoolean("manual", false) &&
                RoseDriveState.allSynced(app, notesBefore)) {
                RoseDriveState.markAlreadyCurrent(app)
                return Result.success()
            }
            // Captura conservadora anterior a crear la copia.
            // Nunca se marcan datos que se modifiquen tras esta captura.
            val recorded = RoseDriveState.snapshot(notesBefore)
            val settingsHash = RoseDriveState.settingsFingerprint(app)
            file = File.createTempFile("notas_backup_", ".rnotas", app.cacheDir)
            file.outputStream().use { output ->
                EncryptedBackups.write(app, output, requireNotNull(passphrase))
            }
            val name = "Notas-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".rnotas"
            RoseDriveApi.backup(app, requireNotNull(authorization.accessToken),
                requireNotNull(file), name)
            // Únicamente después de obtener éxito HTTP en Google Drive.
            RoseDriveState.markUploaded(app, recorded, settingsHash)
            // Si una nota cambió durante la transferencia, guardar un
            // sucesor en la cola, SIN cancelar el Worker que acaba de subir.
            val changesDuringUpload = RoseDriveState.consumeDirty(app)
            if (changesDuringUpload || !RoseDriveState.allSynced(app, dao.getAllForSync())) {
                BackupScheduler.followUp(app)
            }
            Result.success()
        } catch (error: Exception) {
            // Conservar un diagnóstico seguro: nunca registrar mensajes arbitrarios
            // que puedan incluir detalles de la cuenta, tokens o contenido privado.
            val http = Regex("""(?:Google Drive HTTP|Drive session HTTP|Drive HTTP) (\d{3})""")
                .find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            if (http == 401) {
                RoseDriveState.markNeedsAuthorization(app)
                Result.failure()
            } else {
                val reason = when {
                    http != null -> "HTTP $http"
                    error is com.google.android.gms.common.api.ApiException ->
                        "Google Play Services: ${error.statusCode}"
                    error is java.net.UnknownHostException -> "Servidor inaccesible"
                    error is java.net.SocketTimeoutException -> "Tiempo de espera agotado"
                    error is java.net.ConnectException -> "Conexión fallida"
                    error is java.io.IOException -> "Error de red"
                    else -> "Error interno"
                }
                RoseDriveState.markFailed(app, reason)
                if (http != null && http in 400..499 && http != 408 && http != 429)
                    Result.failure()
                else Result.retry()
            }
        } finally {
            file?.delete()
            passphrase?.fill('\u0000')
            lock.unlock()
        }
    }
}
