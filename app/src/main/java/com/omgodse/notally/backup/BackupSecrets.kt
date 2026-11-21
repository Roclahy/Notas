package com.omgodse.notally.backup

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * La contraseña de respaldo solo queda guardada en el dispositivo cifrada
 * con una clave no exportable de Android Keystore (API 23+).
 * Para restaurar en otro dispositivo, el usuario necesita su contraseña.
 */
object BackupSecrets {
    private const val ALIAS = "rocla.notas.encrypted.backup.v1"
    private const val PREFS = "notas_backup_vault"
    private const val KEY_IV = "nonce"
    private const val KEY_SECRET = "encrypted_password"
    fun supported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    private fun key(): SecretKey {
        check(supported())
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val existing = store.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
         .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
         .setKeySize(256).build())
        return generator.generateKey()
    }

    fun save(context: Context, password: CharArray) {
        require(supported() && password.size >= 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(bytes) } finally { bytes.fill(0) }
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putString(KEY_IV,Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .putString(KEY_SECRET,Base64.encodeToString(encrypted,Base64.NO_WRAP))
            .apply()
    }

    fun read(context: Context): CharArray? {
        if (!supported()) return null
        val prefs = context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val iv = prefs.getString(KEY_IV,null) ?: return null
        val data = prefs.getString(KEY_SECRET,null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)))
            val bytes = cipher.doFinal(Base64.decode(data,Base64.NO_WRAP))
            try { String(bytes,Charsets.UTF_8).toCharArray() } finally { bytes.fill(0) }
        } catch (_: Exception) { null }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply()
    }
}
