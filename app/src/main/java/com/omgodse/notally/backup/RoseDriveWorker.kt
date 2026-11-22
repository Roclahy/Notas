package com.omgodse.notally.backup

import android.app.Application
import android.content.Context
import com.omgodse.notally.R
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

        /** Único motor compartido por la ejecución directa y la periódica. */
        fun perform(app: Application, manual: Boolean,
                    includeImages: Boolean = manual || BackupMediaPolicy.includeAutomaticImages(app)):
                    androidx.work.ListenableWorker.Result {
        // El trabajo periódico puede coincidir con la cola por cambios.
        // No bloquear el hilo: la otra subida confirmará o reintentará.
        if (!lock.tryLock()) {
            // Una copia ya está en curso; esa copia programará un sucesor
            // al terminar. No perder una edición en esta carrera.
            RoseDriveState.markDirty(app)
            return androidx.work.ListenableWorker.Result.retry()
        }
        var file: File? = null
        var passphrase: CharArray? = null
        return try {
            if (!RoseDriveState.enabled(app)) return androidx.work.ListenableWorker.Result.success()
            if (!RoseDriveNetwork.hasNetwork(app)) {
                RoseDriveState.markWaitingForConnection(app)
                return androidx.work.ListenableWorker.Result.retry()
            }
            passphrase = BackupSecrets.read(app) ?: run {
                RoseDriveState.markFailed(app, "Contraseña local inaccesible")
                return androidx.work.ListenableWorker.Result.failure()
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
                return androidx.work.ListenableWorker.Result.failure()
            }
            val dao = NotallyDatabase.getDatabase(app).getBaseNoteDao()
            val notesBefore = dao.getAllForSync()
            if (!manual &&
                RoseDriveState.allSynced(app, notesBefore)) {
                RoseDriveState.markAlreadyCurrent(app)
                if (RoseDriveState.consumeDirty(app)) {
                    BackupScheduler.followUp(app)
                }
                return androidx.work.ListenableWorker.Result.success()
            }
            // Captura conservadora anterior a crear la copia.
            // Nunca se marcan datos que se modifiquen tras esta captura.
            val recorded = RoseDriveState.snapshot(notesBefore)
            val settingsHash = RoseDriveState.settingsFingerprint(app)
            file = File.createTempFile("notas_backup_", ".rnotas", app.cacheDir)
            file.outputStream().use { output ->
                EncryptedBackups.write(app, output, requireNotNull(passphrase), includeImages)
            }
            val name = "Notas-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".rnotas"
            RoseDriveApi.backup(app, requireNotNull(authorization.accessToken),
                requireNotNull(file), name)
            // Únicamente después de obtener éxito HTTP en Google Drive.
            RoseDriveState.markUploaded(app, recorded, settingsHash, requireNotNull(file).length())
            // Si una nota cambió durante la transferencia, guardar un
            // sucesor en la cola, SIN cancelar el Worker que acaba de subir.
            val changesDuringUpload = RoseDriveState.consumeDirty(app)
            if (changesDuringUpload || !RoseDriveState.allSynced(app, dao.getAllForSync())) {
                BackupScheduler.followUp(app)
            }
            androidx.work.ListenableWorker.Result.success()
        } catch (error: Exception) {
            // Conservar un diagnóstico seguro: nunca registrar mensajes arbitrarios
            // que puedan incluir detalles de la cuenta, tokens o contenido privado.
            val http = Regex("""(?:Google Drive HTTP|Drive session HTTP|Drive HTTP) (\d{3})""")
                .find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            val lostInternet = !RoseDriveNetwork.hasNetwork(app) ||
                (error is java.net.UnknownHostException &&
                 !RoseDriveNetwork.hasValidatedInternet(app)) ||
                (error is com.google.android.gms.common.api.ApiException &&
                 error.statusCode == com.google.android.gms.common.api.CommonStatusCodes.NETWORK_ERROR &&
                 !RoseDriveNetwork.hasValidatedInternet(app))
            when {
                http == 401 -> {
                    RoseDriveState.markNeedsAuthorization(app)
                    androidx.work.ListenableWorker.Result.failure()
                }
                lostInternet -> {
                    RoseDriveState.markWaitingForConnection(app)
                    androidx.work.ListenableWorker.Result.retry()
                }
                else -> {
                    val reason = when {
                        http != null -> "HTTP $http"
                        error is java.net.UnknownHostException ||
                            error is java.net.ConnectException ||
                            error is java.net.SocketTimeoutException ->
                            app.getString(R.string.google_drive_unreachable)
                        error is com.google.android.gms.common.api.ApiException ->
                            app.getString(R.string.google_drive_play_services_error, error.statusCode)
                        error is java.io.IOException ->
                            app.getString(R.string.google_drive_network_error)
                        else -> app.getString(R.string.google_drive_internal_error)
                    }
                    RoseDriveState.markFailed(app, reason)
                    if (http != null && http in 400..499 && http != 408 && http != 429)
                        androidx.work.ListenableWorker.Result.failure()
                    else androidx.work.ListenableWorker.Result.retry()
                }
            }
        } finally {
            file?.delete()
            passphrase?.fill('\u0000')
            lock.unlock()
        }
        }
    }

    override fun doWork(): Result =
        perform(applicationContext as Application, inputData.getBoolean("manual", false))
}
