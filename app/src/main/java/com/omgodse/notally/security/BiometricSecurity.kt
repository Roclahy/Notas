package com.omgodse.notally.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.omgodse.notally.R

/**
 * Alternativa de autenticación local, opcional y por recurso.
 * No cifra notas ni guarda plantillas biométricas: Android controla la comprobación.
 * La activación de cada nota/carpeta requiere haber desbloqueado su PIN o contraseña.
 */
object BiometricSecurity {
    private const val STORAGE = "notas_biometric_access"
    private fun prefs(context: Context) =
        context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)

    fun supported(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS

    fun isEnabled(context: Context, key: String): Boolean =
        prefs(context).getBoolean("bio:$key", false) && supported(context)

    fun setEnabled(context: Context, key: String, enabled: Boolean) {
        val edit = prefs(context).edit()
        if (enabled) edit.putBoolean("bio:$key", true)
        else edit.remove("bio:$key")
        edit.apply()
    }

    fun authenticate(activity: FragmentActivity, title: String,
                     onAuthenticated: () -> Unit, onFallback: () -> Unit) {
        if (!supported(activity)) {
            onFallback()
            return
        }
        var finished = false
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult
            ) {
                if (finished) return
                finished = true
                onAuthenticated()
            }
            override fun onAuthenticationError(code: Int, message: CharSequence) {
                if (finished) return
                finished = true
                onFallback()
            }
        }
        val prompt = BiometricPrompt(activity,
            ContextCompat.getMainExecutor(activity), callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(activity.getString(R.string.use_pin))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(promptInfo)
    }
}
