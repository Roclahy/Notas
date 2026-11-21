package com.omgodse.notally.security

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM sobre campos privados; solo usa JCA + Android Keystore.
 * Las claves no son exportables. Cada campo utiliza una IV única del Cipher.
 */
object SecureFields {
    private const val alias = "rocla.notas.native.fields.v1"
    private const val prefix = "RSE1:"
    @Volatile private var cachedKey: SecretKey? = null

    fun initialize(app: Application) {
        key()
    }

    private fun key(): SecretKey = cachedKey ?: synchronized(this) {
        cachedKey ?: run {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(alias, null) as? SecretKey
            val current = existing ?: KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                    init(KeyGenParameterSpec.Builder(alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build())
                    generateKey()
                }
            cachedKey = current
            current
        }
    }

    fun seal(text: String, field: String): String {
        if (text.isEmpty()) return text
        require(!text.startsWith(prefix)) { "Refusing double-encryption: $field" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(field.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return prefix + Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    fun open(value: String, field: String): String {
        if (!value.startsWith(prefix)) return value // Legacy plaintext migration only.
        val bytes = Base64.decode(value.removePrefix(prefix), Base64.NO_WRAP)
        require(bytes.size >= 28) { "Incomplete encrypted field" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(field.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }

    fun isSealed(value: String): Boolean = value.startsWith(prefix)
}
