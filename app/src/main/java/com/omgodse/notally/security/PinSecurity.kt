package com.omgodse.notally.security

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Control de acceso por PIN. No cifra las notas ni sus adjuntos almacenados.
 * Usa salt y PBKDF2 para no conservar el PIN en texto plano.
 */
object PinSecurity {
    private const val PREFS = "notas_access_control"
    private const val APP_KEY = "pin:app"
    @Volatile var appUnlocked = false
    // Monotonic time, never system wall-clock time (resistant to date changes).
    @Volatile private var backgroundAtMs = -1L
    private val allowedRelockSeconds = setOf(5, 10, 30, 60, 300)
    private val random = SecureRandom()

    fun appRelockDelaySeconds(context: Context): Int =
        prefs(context).getInt("app:relock_seconds", 5)
            .takeIf { it in allowedRelockSeconds } ?: 5

    fun setAppRelockDelaySeconds(context: Context, seconds: Int) {
        require(seconds in allowedRelockSeconds)
        prefs(context).edit().putInt("app:relock_seconds", seconds).apply()
    }

    /** Recordar cuándo toda la aplicación deja de estar visible, no entre pantallas internas. */
    fun onAppWentToBackground() {
        backgroundAtMs = SystemClock.elapsedRealtime()
    }

    /** Comprobar al volver: el bloqueo se pide solo cuando ya venció el intervalo. */
    fun onAppReturnedToForeground(context: Context) {
        val backgroundAt = backgroundAtMs
        backgroundAtMs = -1L
        if (appEnabled(context) && backgroundAt >= 0L &&
            SystemClock.elapsedRealtime() - backgroundAt >=
                appRelockDelaySeconds(context) * 1000L) {
            appUnlocked = false
        }
    }

    // El PIN vive fuera de Room: los cambios deben avisar a las tarjetas
    // aunque el contenido de la nota no haya sido modificado.
    private val mutableNoteChanges = MutableLiveData<Long>()
    val noteChanges: LiveData<Long> get() = mutableNoteChanges

    fun appEnabled(context: Context): Boolean = enabled(context, APP_KEY)
    fun noteLocked(context: Context, id: Long): Boolean = id > 0 && enabled(context, noteKey(id))
    fun noteKey(id: Long): String = "pin:note:$id"

    /** Solo hashes PBKDF2; jamás incluye PIN sin cifrar ni contadores. */
    fun backupHashes(context: Context): Map<Long, String> =
        prefs(context).all.mapNotNull { (key, value) ->
            val id = key.removePrefix("pin:note:").takeIf {
                key.startsWith("pin:note:")
            }?.toLongOrNull()
            val hash = value as? String
            if (id != null && id > 0L && hash?.contains(':') == true) {
                id to hash
            } else null
        }.toMap()

    fun restoreHashes(context: Context, oldToNew: Map<Long, Long>,
                      hashes: Map<Long, String>) {
        val edit = prefs(context).edit()
        var changed = false
        for ((oldId, value) in hashes) {
            val newId = oldToNew[oldId] ?: continue
            val parts = value.split(':')
            if (newId <= 0L || parts.size != 2) continue
            // Rechazar cadenas arbitrarias o malformadas en un respaldo externo.
            val valid = try {
                Base64.decode(parts[0], Base64.NO_WRAP).size == 16 &&
                    Base64.decode(parts[1], Base64.NO_WRAP).size == 32
            } catch (_: Exception) { false }
            if (!valid) continue
            edit.putString(noteKey(newId), value)
            changed = true
        }
        if (changed) {
            check(edit.commit()) { "Failed to restore note PIN hashes" }
            mutableNoteChanges.postValue(-1L)
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun enabled(c: Context, key: String) = prefs(c).contains(key)

    fun setAppPin(context: Context, pin: String) {
        store(context, APP_KEY, pin)
        appUnlocked = true
    }

    fun setNotePin(context: Context, id: Long, pin: String) {
        require(id > 0L) { "A note must be saved before it can be locked" }
        store(context, noteKey(id), pin)
        mutableNoteChanges.postValue(id)
    }
    fun removeAppPin(context: Context) {
        clear(context, APP_KEY)
        BiometricSecurity.setEnabled(context, APP_KEY, false)
        appUnlocked = true
    }
    fun removeNotePin(context: Context, id: Long) {
        clear(context, noteKey(id))
        BiometricSecurity.setEnabled(context, noteKey(id), false)
        mutableNoteChanges.postValue(id)
    }

    private fun clear(c: Context, key: String) {
        prefs(c).edit().remove(key).remove("$key:attempts").remove("$key:until").apply()
    }

    private fun store(c: Context, key: String, pin: String) {
        require(pin.length == 4 && pin.all(Char::isDigit))
        val salt = ByteArray(16).also(random::nextBytes)
        val digest = derive(pin, salt)
        val value = Base64.encodeToString(salt, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(digest, Base64.NO_WRAP)
        prefs(c).edit().putString(key, value).remove("$key:attempts").remove("$key:until").apply()
        digest.fill(0)
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val pass = pin.toCharArray()
        val spec = PBEKeySpec(pass, salt, 150_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            pass.fill('\u0000')
        }
    }

    fun remainingLockout(c: Context, key: String): Long {
        val until = prefs(c).getLong("$key:until", 0L)
        return ((until - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0L)
    }

    fun verify(c: Context, key: String, pin: String): Boolean {
        val stored = prefs(c).getString(key, null) ?: return false
        if (remainingLockout(c, key) > 0L) return false
        if (pin.length != 4 || !pin.all(Char::isDigit)) return false
        val components = stored.split(':')
        if (components.size != 2) return false
        val matched = try {
            val salt = Base64.decode(components[0], Base64.NO_WRAP)
            val expected = Base64.decode(components[1], Base64.NO_WRAP)
            val candidate = derive(pin, salt)
            val result = MessageDigest.isEqual(expected, candidate)
            candidate.fill(0)
            result
        } catch (_: IllegalArgumentException) { false }
        if (matched) {
            prefs(c).edit().remove("$key:attempts").remove("$key:until").apply()
        } else {
            val count = prefs(c).getInt("$key:attempts", 0) + 1
            if (count >= 5) {
                prefs(c).edit().putInt("$key:attempts", 0)
                    .putLong("$key:until", SystemClock.elapsedRealtime() + 60_000L).apply()
            } else prefs(c).edit().putInt("$key:attempts", count).apply()
        }
        return matched
    }
}
