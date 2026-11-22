package com.omgodse.notally.fragments

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.omgodse.notally.backup.BackupScheduler
import com.omgodse.notally.backup.BackupMediaPolicy
import com.omgodse.notally.backup.BackupSecrets
import com.omgodse.notally.backup.EncryptedBackups
import com.omgodse.notally.backup.RoseDriveState
import com.omgodse.notally.backup.RoseDriveApi
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.omgodse.notally.security.PinSecurity
import com.omgodse.notally.security.PinDialogs
import com.omgodse.notally.security.BiometricSecurity
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.MutableLiveData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.omgodse.notally.MenuDialog
import com.omgodse.notally.Progress
import com.omgodse.notally.R
import com.omgodse.notally.databinding.DialogProgressBinding
import com.omgodse.notally.databinding.FragmentSettingsBinding
import com.omgodse.notally.databinding.PreferenceBinding
import com.omgodse.notally.databinding.PreferenceSeekbarBinding
import com.omgodse.notally.miscellaneous.Operations
import com.omgodse.notally.preferences.*
import com.omgodse.notally.preferences.RoseLanguage
import com.omgodse.notally.viewmodels.BaseNoteModel

class Settings : Fragment() {

    private val model: BaseNoteModel by activityViewModels()
    private var refreshDriveLabel: (() -> Unit)? = null

    private val driveAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { response ->
        if (response.resultCode != Activity.RESULT_OK) {
            BackupSecrets.clear(requireContext())
            Toast.makeText(requireContext(),R.string.google_drive_authorization_failed,Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        try {
            val token = Identity.getAuthorizationClient(requireContext())
                .getAuthorizationResultFromIntent(response.data)
            if (token.accessToken.isNullOrEmpty()) {
                BackupSecrets.clear(requireContext())
                Toast.makeText(requireContext(),R.string.google_drive_authorization_failed,Toast.LENGTH_LONG).show()
            } else enableDriveBackups()
        } catch (_: Exception) {
            BackupSecrets.clear(requireContext())
            Toast.makeText(requireContext(),R.string.google_drive_authorization_failed,Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDriveLabel?.invoke()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentSettingsBinding.bind(view)
        setupBinding(binding)
        // En Liquid Glass el contenido permanece en superficies mates.
        // Las lentes se reservan a controles flotantes, no a todo Ajustes.
        RoseDriveState.revision.observe(viewLifecycleOwner) { refreshDriveLabel?.invoke() }
    }

    override fun onDestroyView() {
        refreshDriveLabel = null
        super.onDestroyView()
    }

    private fun updateDriveRow(binding: FragmentSettingsBinding) {
        val enabled = RoseDriveState.enabled(requireContext())
        binding.AutoBackup.Title.setText(R.string.google_drive_backup)
        val context = requireContext()
        val status = when {
            !enabled -> getString(R.string.google_drive_connect)
            RoseDriveState.needsAuthorization(context) ->
                getString(R.string.google_drive_needs_auth)
            RoseDriveState.isUploading(context) ->
                getString(R.string.google_drive_uploading)
            RoseDriveState.isWaitingForConnection(context) ->
                getString(R.string.google_drive_waiting_connection)
            RoseDriveState.lastFailure(context) != null ->
                getString(R.string.google_drive_upload_error, RoseDriveState.lastFailure(context))
            RoseDriveState.isQueued(context) ->
                getString(R.string.google_drive_queued)
            RoseDriveState.lastUpload(context) > 0L -> {
                val date = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                ).format(java.util.Date(RoseDriveState.lastUpload(context)))
                getString(R.string.google_drive_last_upload, date)
            }
            else -> getString(R.string.google_drive_pending)
        }
        val bytes = RoseDriveState.lastBackupBytes(context)
        binding.AutoBackup.Value.text = if (bytes > 0L) {
            status + " · " + android.text.format.Formatter.formatShortFileSize(context, bytes)
        } else status
        binding.AutoBackup.root.setOnClickListener {
            if (!enabled || RoseDriveState.needsAuthorization(requireContext())) {
                connectGoogleDrive()
            } else {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.google_drive_backup)
                    .setItems(arrayOf(
                        getString(R.string.notas_backup_manual_images),
                        getString(R.string.notas_backup_manual_no_images),
                        getString(R.string.notas_auto_images) + ": " +
                            (if (BackupMediaPolicy.includeAutomaticImages(requireContext())) "✓" else "—"),
                        getString(R.string.google_drive_restore),
                        getString(R.string.google_drive_disconnect)
                    )) { _, choice ->
                        when (choice) {
                            0, 1 -> {
                                if (BackupScheduler.force(requireActivity().application,
                                        includeImages = choice == 0)) {
                                    Toast.makeText(requireContext(), R.string.sync_queued,
                                        Toast.LENGTH_SHORT).show()
                                }
                            }
                            2 -> {
                                val now = BackupMediaPolicy.includeAutomaticImages(requireContext())
                                BackupMediaPolicy.setAutomaticImages(requireContext(), !now)
                                BackupScheduler.changed(requireActivity().application)
                                updateDriveRow(binding)
                            }
                            3 -> restoreFromGoogleDrive()
                            else -> {
                                RoseDriveState.setEnabled(requireContext(), false)
                                BackupScheduler.disable(requireActivity().application)
                                BackupSecrets.clear(requireContext())
                                updateDriveRow(binding)
                            }
                        }
                    }.show()
            }
        }
    }

    private suspend fun driveToken(): String = withContext(Dispatchers.IO) {
        val request = AuthorizationRequest.builder().setRequestedScopes(
            listOf(Scope("https://www.googleapis.com/auth/drive.file"))
        ).build()
        val response = Tasks.await(
            Identity.getAuthorizationClient(requireContext()).authorize(request),
            45L, TimeUnit.SECONDS)
        if (response.hasResolution() || response.accessToken.isNullOrBlank()) {
            throw IllegalStateException("Authorization required")
        }
        requireNotNull(response.accessToken)
    }

    private fun restoreFromGoogleDrive() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val backups = withContext(Dispatchers.IO) {
                    RoseDriveApi.listBackups(driveToken())
                }
                if (backups.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.google_drive_no_backups,
                        Toast.LENGTH_LONG).show()
                    return@launch
                }
                val labels = backups.map {
                    if (it.bytes > 0L) it.name + " · " +
                        android.text.format.Formatter.formatShortFileSize(requireContext(), it.bytes)
                    else it.name
                }.toTypedArray()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.google_drive_restore)
                    .setItems(labels) { _, selected ->
                        val item = backups[selected]
                        requestBackupPassword(confirm = false) { password ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                val app = requireActivity().application
                                var encrypted: File? = null
                                var decrypted: File? = null
                                try {
                                    decrypted = withContext(Dispatchers.IO) {
                                        val transfer = File.createTempFile(
                                            "drive_recovery_", ".rnotas", app.cacheDir)
                                        encrypted = transfer
                                        RoseDriveApi.download(driveToken(), item.id, transfer)
                                        transfer.inputStream().use { input ->
                                            EncryptedBackups.decryptToCache(app, input, password)
                                        }
                                    }
                                    importDecryptedBackup(requireNotNull(decrypted))
                                } catch (_: Exception) {
                                    decrypted?.delete()
                                    Toast.makeText(app, R.string.google_drive_restore_failed,
                                        Toast.LENGTH_LONG).show()
                                } finally {
                                    encrypted?.delete()
                                    password.fill('\u0000')
                                }
                            }
                        }
                    }.show()
            } catch (_: Exception) {
                Toast.makeText(requireContext(), R.string.google_drive_authorization_needed,
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importDecryptedBackup(restored: File) {
        val app = requireActivity().application
        var started = false
        val observer = object : Observer<Progress> {
            override fun onChanged(progress: Progress) {
                if (progress.inProgress) started = true
                else if (started) {
                    model.importingBackup.removeObserver(this)
                    if (model.importedBackupSuccessfully.value == true) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    EncryptedBackups.restoreSettings(app, restored)
                                }
                                Toast.makeText(app, R.string.encrypted_restore_complete,
                                    Toast.LENGTH_LONG).show()
                            } finally { restored.delete() }
                        }
                    } else restored.delete()
                }
            }
        }
        model.importingBackup.observe(viewLifecycleOwner, observer)
        val safeUri = FileProvider.getUriForFile(
            app, "${app.packageName}.provider", restored)
        model.importBackup(safeUri)
    }

    private fun enableDriveBackups() {
        RoseDriveState.setEnabled(requireContext(), true)
        RoseDriveState.markAuthorized(requireContext())
        BackupScheduler.schedule(requireActivity().application, refresh = true)
        refreshDriveLabel?.invoke()
        Toast.makeText(requireContext(),R.string.google_drive_authorized,Toast.LENGTH_LONG).show()
    }

    private fun connectGoogleDrive() {
        if (!BackupSecrets.supported()) {
            Toast.makeText(requireContext(),R.string.backup_requires_android_6,Toast.LENGTH_LONG).show()
            return
        }
        requestBackupPassword(confirm = true) { passphrase ->
            try {
                BackupSecrets.save(requireContext(), passphrase)
                val request = AuthorizationRequest.builder().setRequestedScopes(
                    listOf(Scope("https://www.googleapis.com/auth/drive.file"))
                ).build()
                Identity.getAuthorizationClient(requireContext()).authorize(request)
                    .addOnSuccessListener { result ->
                        if (result.hasResolution()) {
                            val pending = result.pendingIntent
                            if (pending == null) {
                                BackupSecrets.clear(requireContext())
                                Toast.makeText(requireContext(),R.string.google_drive_authorization_failed,Toast.LENGTH_LONG).show()
                            } else {
                                driveAuthorizationLauncher.launch(
                                    IntentSenderRequest.Builder(pending.intentSender).build()
                                )
                            }
                        } else if (!result.accessToken.isNullOrBlank()) {
                            enableDriveBackups()
                        } else {
                            BackupSecrets.clear(requireContext())
                            Toast.makeText(requireContext(),R.string.google_drive_authorization_failed,Toast.LENGTH_LONG).show()
                        }
                    }
                    .addOnFailureListener {
                        BackupSecrets.clear(requireContext())
                        Toast.makeText(requireContext(),R.string.google_drive_authorization_failed,Toast.LENGTH_LONG).show()
                    }
            } catch (_: Exception) {
                BackupSecrets.clear(requireContext())
                Toast.makeText(requireContext(),R.string.google_drive_require_google,Toast.LENGTH_LONG).show()
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    private fun setupBinding(binding: FragmentSettingsBinding) {
        model.preferences.view.observe(viewLifecycleOwner) { value ->
            binding.View.setup(View, value)
        }

        model.preferences.theme.observe(viewLifecycleOwner) { value ->
            binding.Theme.setup(Theme, value)
        }

        fun updateLanguage() {
            binding.Language.Title.setText(R.string.app_language)
            binding.Language.Value.text = when (RoseLanguage.selected(requireContext())) {
                RoseLanguage.SPANISH -> getString(R.string.language_spanish)
                RoseLanguage.ENGLISH -> getString(R.string.language_english)
                else -> getString(R.string.language_system)
            }
        }
        updateLanguage()
        binding.Language.root.setOnClickListener {
            val choices = arrayOf(
                getString(R.string.language_system),
                getString(R.string.language_spanish),
                getString(R.string.language_english)
            )
            val values = arrayOf(RoseLanguage.SYSTEM, RoseLanguage.SPANISH, RoseLanguage.ENGLISH)
            val selected = values.indexOf(RoseLanguage.selected(requireContext())).coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.app_language)
                .setSingleChoiceItems(choices, selected) { dialog, which ->
                    dialog.dismiss()
                    if (values[which] != RoseLanguage.selected(requireContext()) &&
                        RoseLanguage.set(requireContext(), values[which])) {
                        requireActivity().recreate()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        model.preferences.dateFormat.observe(viewLifecycleOwner) { value ->
            binding.DateFormat.setup(DateFormat, value)
        }

        model.preferences.textSize.observe(viewLifecycleOwner) { value ->
            binding.TextSize.setup(TextSize, value)
        }


        binding.MaxItems.setup(MaxItems, model.preferences.maxItems)

        binding.MaxLines.setup(MaxLines, model.preferences.maxLines)

        binding.MaxTitle.setup(MaxTitle, model.preferences.maxTitle)


        // Drive es el único destino del respaldo automático.
        // El selector SAF se conserva exclusivamente para importación/exportación manual.
        refreshDriveLabel = { updateDriveRow(binding) }
        updateDriveRow(binding)

        // No hay exportación ni respaldos locales: Drive cifrado es el destino.
        setupProgressDialog(R.string.exporting_backup, model.exportingBackup)
        setupProgressDialog(R.string.importing_backup, model.importingBackup)

        fun updatePinLabel() {
            val enabled = PinSecurity.appEnabled(requireContext())
            binding.AppPin.setText(if (enabled) R.string.disable_app_pin else R.string.enable_app_pin)
            binding.AppBiometric.visibility =
                if (enabled && BiometricSecurity.supported(requireContext())) View.VISIBLE else View.GONE
            binding.AppBiometric.setText(
                if (BiometricSecurity.isEnabled(requireContext(), "pin:app"))
                    R.string.disable_app_biometric else R.string.enable_app_biometric)
            binding.AppLockDelay.visibility = if (enabled) View.VISIBLE else View.GONE
            val delay = PinSecurity.appRelockDelaySeconds(requireContext())
            val choice = when (delay) {
                5 -> R.string.lock_delay_5
                10 -> R.string.lock_delay_10
                30 -> R.string.lock_delay_30
                60 -> R.string.lock_delay_60
                else -> R.string.lock_delay_300
            }
            binding.AppLockDelay.text =
                getString(R.string.lock_delay_setting, getString(choice))
        }
        updatePinLabel()
        binding.AppPin.setOnClickListener {
            if (PinSecurity.appEnabled(requireContext())) {
                PinDialogs.check(requireActivity(), "pin:app", onVerified = {
                    PinSecurity.removeAppPin(requireContext())
                    com.omgodse.notally.widget.WidgetProvider.sendBroadcast(
                        requireActivity().application, longArrayOf())
                    updatePinLabel()
                })
            } else {
                PinDialogs.setup(requireActivity()) { pin ->
                    PinSecurity.setAppPin(requireContext(), pin)
                    com.omgodse.notally.widget.WidgetProvider.sendBroadcast(
                        requireActivity().application, longArrayOf())
                    updatePinLabel()
                }
            }
        }

        binding.AppBiometric.setOnClickListener {
            if (!PinSecurity.appEnabled(requireContext())) return@setOnClickListener
            PinDialogs.check(requireActivity(), "pin:app", onVerified = {
                if (BiometricSecurity.isEnabled(requireContext(), "pin:app")) {
                    BiometricSecurity.setEnabled(requireContext(), "pin:app", false)
                    updatePinLabel()
                } else {
                    BiometricSecurity.authenticate(requireActivity(),
                        getString(R.string.biometric_unlock), onAuthenticated = {
                            BiometricSecurity.setEnabled(requireContext(), "pin:app", true)
                            updatePinLabel()
                        }, onFallback = {})
                }
            })
        }

        binding.AppLockDelay.setOnClickListener {
            if (!PinSecurity.appEnabled(requireContext())) return@setOnClickListener
            val intervals = intArrayOf(5, 10, 30, 60, 300)
            val labels = arrayOf(
                getString(R.string.lock_delay_5), getString(R.string.lock_delay_10),
                getString(R.string.lock_delay_30), getString(R.string.lock_delay_60),
                getString(R.string.lock_delay_300)
            )
            val current = intervals.indexOf(PinSecurity.appRelockDelaySeconds(requireContext()))
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lock_delay_title)
                .setSingleChoiceItems(labels, current) { dialog, selected ->
                    PinSecurity.setAppRelockDelaySeconds(requireContext(), intervals[selected])
                    updatePinLabel()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // La autoría es texto informativo; el canal abre Telegram.
        binding.TelegramChannel.setOnClickListener {
            openLink("https://t.me/notas_app")
        }
        binding.Website.setOnClickListener {
            openLink("https://notas.roclahy.com/")
        }
        binding.SourceCode.setOnClickListener {
            openLink("https://github.com/Roclahy/Notas")
        }
        binding.PrivacyPolicy.setOnClickListener {
            openLink("https://notas.roclahy.com/privacidad/")
        }

        binding.Version.text = "${requireContext().getString(R.string.version_label)} ${com.omgodse.notally.BuildConfig.VERSION_NAME}"

    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentSettingsBinding.inflate(inflater, container, false).root
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            intent?.data?.let { uri ->
                when (requestCode) {
                    REQUEST_IMPORT_BACKUP -> importSelectedBackup(uri)
                    REQUEST_EXPORT_BACKUP -> exportEncryptedBackup(uri)
                    REQUEST_CHOOSE_FOLDER -> {
                        try {
                            model.setAutoBackupPath(uri)
                            BackupScheduler.schedule(requireActivity().application, uri, refresh = true)
                        } catch (_: Exception) {
                            Toast.makeText(requireContext(),R.string.backup_provider_unavailable,Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }


    private fun exportBackup() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/octet-stream"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, "Notas-" + java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US)
                .format(System.currentTimeMillis()) + ".rnotas")
        }
        startActivityForResult(intent, REQUEST_EXPORT_BACKUP)
    }

    private fun importBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/octet-stream","application/zip","text/xml"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQUEST_IMPORT_BACKUP)
    }

    private fun requestBackupPassword(confirm: Boolean, callback: (CharArray) -> Unit) {
        val context = requireContext()
        val dp = resources.displayMetrics.density
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22*dp).toInt(),(8*dp).toInt(),(22*dp).toInt(),(10*dp).toInt())
        }
        fun field(hint: Int): EditText = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setHint(hint)
            isSingleLine = true
            minHeight = (55*dp).toInt()
        }
        val first = field(R.string.backup_password)
        panel.addView(first)
        val repeat = if (confirm) field(R.string.confirm_backup_password) else null
        repeat?.let(panel::addView)
        val message = android.widget.TextView(context).apply {
            setText(R.string.backup_password_warning)
            textSize = 12f
            setPadding(0,(13*dp).toInt(),0,0)
        }
        panel.addView(message)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (confirm) R.string.create_backup_password else R.string.enter_backup_password)
            .setView(panel)
            .setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.continue_,null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val pass = first.text.toString()
                if (pass.length < 12) {
                    first.error = getString(R.string.backup_password_minimum)
                } else if (repeat != null && pass != repeat.text.toString()) {
                    repeat.error = getString(R.string.pin_mismatch)
                } else {
                    val password = pass.toCharArray()
                    first.text?.clear()
                    repeat?.text?.clear()
                    dialog.dismiss()
                    callback(password)
                }
            }
        }
        dialog.show()
    }

    private fun exportEncryptedBackup(uri: Uri) {
        requestBackupPassword(confirm = true) { pass ->
            viewLifecycleOwner.lifecycleScope.launch {
                val app = requireActivity().application
                try {
                    withContext(Dispatchers.IO) {
                        requireNotNull(app.contentResolver.openOutputStream(uri,"w")).use { out ->
                            EncryptedBackups.write(app,out,pass)
                        }
                    }
                    Toast.makeText(app,R.string.encrypted_backup_saved,Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    Toast.makeText(app,R.string.encrypted_backup_error,Toast.LENGTH_LONG).show()
                } finally { pass.fill('\u0000') }
            }
        }
    }

    private fun importSelectedBackup(uri: Uri) {
        val app = requireActivity().application
        viewLifecycleOwner.lifecycleScope.launch {
            val encrypted = withContext(Dispatchers.IO) { EncryptedBackups.isEncrypted(app,uri) }
            if (!encrypted) {
                // Compatibilidad con copias ZIP/XML antiguas seleccionadas expresamente.
                model.importBackup(uri)
                return@launch
            }
            requestBackupPassword(confirm = false) { password ->
                viewLifecycleOwner.lifecycleScope.launch {
                    var file: File? = null
                    try {
                        file = withContext(Dispatchers.IO) {
                            requireNotNull(app.contentResolver.openInputStream(uri)).use { input ->
                                EncryptedBackups.decryptToCache(app,input,password)
                            }
                        }
                        val restored = requireNotNull(file)
                        var started = false
                        val observer = object : Observer<Progress> {
                            override fun onChanged(progress: Progress) {
                                if (progress.inProgress) started = true
                                else if (started) {
                                    model.importingBackup.removeObserver(this)
                                    if (model.importedBackupSuccessfully.value == true) {
                                        viewLifecycleOwner.lifecycleScope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    EncryptedBackups.restoreSettings(app,restored)
                                                }
                                                Toast.makeText(app,R.string.encrypted_restore_complete,Toast.LENGTH_LONG).show()
                                            } finally { restored.delete() }
                                        }
                                    } else restored.delete()
                                }
                            }
                        }
                        model.importingBackup.observe(viewLifecycleOwner, observer)
                        val safeUri = FileProvider.getUriForFile(app,"${app.packageName}.provider",restored)
                        model.importBackup(safeUri)
                    } catch (_: Exception) {
                        file?.delete()
                        Toast.makeText(app,R.string.wrong_backup_password,Toast.LENGTH_LONG).show()
                    } finally { password.fill('\u0000') }
                }
            }
        }
    }

    private fun setupProgressDialog(titleId: Int, liveData: MutableLiveData<Progress>) {
        val dialogBinding = DialogProgressBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleId)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        liveData.observe(viewLifecycleOwner) { progress ->
            if (progress.inProgress) {
                if (progress.indeterminate) {
                    dialogBinding.ProgressBar.isIndeterminate = true
                    dialogBinding.Count.setText(R.string.calculating)
                } else {
                    dialogBinding.ProgressBar.max = progress.total
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        dialogBinding.ProgressBar.setProgress(progress.current, true)
                    } else dialogBinding.ProgressBar.progress = progress.current
                    dialogBinding.Count.text = getString(R.string.count, progress.current, progress.total)
                }
                dialog.show()
            } else dialog.dismiss()
        }
    }


    private fun sendEmailWithLog() {
        val intent = Intent(Intent.ACTION_SEND)
        intent.selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))

        intent.putExtra(Intent.EXTRA_EMAIL, arrayOf<String>())
        intent.putExtra(Intent.EXTRA_SUBJECT, "Notas [Comentarios]")

        val app = requireContext().applicationContext as Application
        val log = Operations.getLog(app)
        if (log.exists()) {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.provider", log)
            intent.putExtra(Intent.EXTRA_STREAM, uri)
        }

        try {
            startActivity(intent)
        } catch (exception: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.install_an_email, Toast.LENGTH_LONG).show()
        }
    }

    private fun displayLibraries() {
        val libraries = arrayOf("Glide", "Pretty Time", "Swipe Layout", "Work Manager", "Subsampling Scale ImageView" ,"Material Components for Android")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.libraries)
            .setItems(libraries) { _, which ->
                when (which) {
                    0 -> openLink("https://github.com/bumptech/glide")
                    1 -> openLink("https://github.com/ocpsoft/prettytime")
                    2 -> openLink("https://github.com/rambler-digital-solutions/swipe-layout-android")
                    3 -> openLink("https://developer.android.com/jetpack/androidx/releases/work")
                    4 -> openLink("https://github.com/davemorrissey/subsampling-scale-image-view")
                    5 -> openLink("https://github.com/material-components/material-components-android")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun displayChooseFolderDialog() {
        connectGoogleDrive() // Compatibilidad con el manejador de preferencias anterior.
    }

    private fun PreferenceBinding.setup(info: ListInfo, value: String) {
        Title.setText(info.title)

        val entries = info.getEntries(requireContext())
        val entryValues = info.getEntryValues()

        val checked = entryValues.indexOf(value)
        val displayValue = entries[checked]

        Value.text = displayValue

        root.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(info.title)
                .setSingleChoiceItems(entries, checked) { dialog, which ->
                    dialog.cancel()
                    val newValue = entryValues[which]
                    if (info == Theme) {
                        // Aplicamos la preferencia antes de recrear para elegir el tema AMOLED.
                        com.omgodse.notally.preferences.Preferences.getInstance(requireActivity().application).savePreference(info, newValue)
                        requireActivity().recreate()
                    } else model.savePreference(info, newValue)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun PreferenceBinding.setup(info: AutoBackup, value: String) {
        Title.setText(info.title)

        if (value == info.emptyPath) {
            Value.setText(R.string.tap_to_set_up)

            root.setOnClickListener { displayChooseFolderDialog() }
        } else {
            val uri = Uri.parse(value)
            val folder = requireNotNull(DocumentFile.fromTreeUri(requireContext(), uri))
            if (folder.exists()) {
                Value.text = folder.name
            } else Value.setText(R.string.cant_find_folder)

            root.setOnClickListener {
                MenuDialog(requireContext())
                    .add(R.string.disable_auto_backup) { model.disableAutoBackup() }
                    .add(R.string.choose_another_folder) { displayChooseFolderDialog() }
                    .show()
            }
        }
    }

    private fun PreferenceSeekbarBinding.setup(info: SeekbarInfo, initialValue: Int) {
        Title.setText(info.title)

        Slider.valueTo = info.max.toFloat()
        Slider.valueFrom = info.min.toFloat()

        Slider.value = initialValue.toFloat()

        Slider.addOnChangeListener { _, value, _ ->
            model.savePreference(info, value.toInt())
        }
    }


    private fun openLink(link: String) {
        val uri = Uri.parse(link)
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent)
        } catch (exception: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.install_a_browser, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQUEST_IMPORT_BACKUP = 20
        private const val REQUEST_EXPORT_BACKUP = 21
        private const val REQUEST_CHOOSE_FOLDER = 22
    }
}