package com.omgodse.notally

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.omgodse.notally.backup.BackupSecrets
import com.omgodse.notally.backup.EncryptedBackups
import com.omgodse.notally.miscellaneous.Operations
import com.omgodse.notally.preferences.AutoBackup
import com.omgodse.notally.preferences.Preferences
import java.text.SimpleDateFormat
import java.util.Locale

class AutoBackupWorker(private val context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = context.applicationContext as Application
        val savedPath = Preferences.getInstance(app).autoBackup.value
        if (savedPath == AutoBackup.emptyPath) return Result.success()
        // Si aún no se configuró una contraseña, nunca crear un respaldo sin cifrar.
        val password = BackupSecrets.read(app) ?: return Result.failure()
        var file: DocumentFile? = null
        return try {
            val folder = requireNotNull(DocumentFile.fromTreeUri(app,Uri.parse(savedPath)))
            if (!folder.exists() || !folder.canWrite()) return Result.retry()
            val name = SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(System.currentTimeMillis())
            file = requireNotNull(folder.createFile("application/octet-stream","Notas-$name.rnotas"))
            requireNotNull(app.contentResolver.openOutputStream(requireNotNull(file).uri,"w")).use { output ->
                EncryptedBackups.write(app,output,password)
            }
            Result.success()
        } catch (e: Exception) {
            file?.delete() // No dejar copias incompletas en el proveedor.
            Operations.log(app,e)
            Result.retry()
        } finally {
            password.fill('\u0000')
        }
    }
}
