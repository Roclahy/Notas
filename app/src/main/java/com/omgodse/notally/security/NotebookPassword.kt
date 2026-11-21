package com.omgodse.notally.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Verificación de contraseña de carpeta. El digest se incluye en la base de
 * datos, por lo que viaja dentro de la copia de Google Drive ya cifrada.
 * No confundir esta verificación con el cifrado independiente de los datos.
 */
object NotebookPassword {
    private const val iterations = 250_000
    fun hash(password: CharArray): String {
        require(password.size >= 12)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hash = derive(password, salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(hash, Base64.NO_WRAP).also { hash.fill(0) }
    }

    fun verify(password: CharArray, encoded: String): Boolean {
        if (encoded.isBlank()) return true
        val parts = encoded.split(':')
        if (parts.size != 2) return false
        return try {
            val salt = Base64.decode(parts[0], Base64.NO_WRAP)
            val expected = Base64.decode(parts[1], Base64.NO_WRAP)
            val actual = derive(password, salt)
            try { MessageDigest.isEqual(expected, actual) } finally { actual.fill(0) }
        } catch (_: Exception) { false }
    }

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally { spec.clearPassword() }
    }
}
