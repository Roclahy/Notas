package com.omgodse.notally.backup

import android.content.Context
import android.preference.PreferenceManager
import androidx.lifecycle.MutableLiveData
import com.omgodse.notally.room.BaseNote
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Estado verificable del último respaldo cifrado que Drive aceptó con HTTP 2xx.
 * "Sincronizado" significa incluido en ese respaldo, NO edición bidireccional.
 *
 * No se almacena contenido, título, correo, PIN ni token en estas preferencias.
 * Solo SHA-256 de cada versión de nota; el ZIP cifrado se sube por separado.
 */
object RoseDriveState {
    private const val PREFS = "rose_google_drive"
    private const val BACKED_UP = "uploaded_note_hashes"
    private const val BACKED_UP_SETTINGS = "uploaded_settings_hash"
    val trackedSettings = setOf("theme", "view", "dateFormat", "textSize", "maxItems", "maxLines", "maxTitle")
    val revision = MutableLiveData(0L)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, enabled: Boolean) {
        val editor = prefs(c).edit().putBoolean("enabled", enabled)
        if (!enabled) {
            editor.remove(BACKED_UP).remove(BACKED_UP_SETTINGS).remove("last_upload").remove("needs_authorization")
        }
        editor.apply()
        invalidate()
    }

    fun fingerprint(note: BaseNote): String {
        // data class toString() comprende cuerpo, título, tareas, medios,
        // carpeta, etiquetas, pin, recordatorios y formato; no depende del timestamp.
        val input = note.toString().toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(input)
        return hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun snapshot(notes: List<BaseNote>): Map<Long, String> =
        notes.associate { it.id to fingerprint(it) }

    fun settingsFingerprint(c: Context): String {
        val values = PreferenceManager.getDefaultSharedPreferences(c).all
        val canonical = trackedSettings.sorted().joinToString("\\n") { key ->
            key + "=" + (values[key]?.toString() ?: "")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun previous(c: Context): Map<Long, String> {
        val encoded = prefs(c).getString(BACKED_UP, null) ?: return emptyMap()
        return try {
            val json = JSONObject(encoded)
            val out = LinkedHashMap<Long, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key.toLong()] = json.getString(key)
            }
            out
        } catch (_: Exception) { emptyMap() }
    }

    fun noteSynced(c: Context, note: BaseNote): Boolean =
        enabled(c) && !needsAuthorization(c) && lastUpload(c) > 0 &&
            previous(c)[note.id] == fingerprint(note)

    fun allSynced(c: Context, notes: List<BaseNote>): Boolean =
        enabled(c) && !needsAuthorization(c) && lastUpload(c) > 0 &&
            previous(c) == snapshot(notes) &&
            prefs(c).getString(BACKED_UP_SETTINGS, null) == settingsFingerprint(c)

    fun markUploaded(c: Context, recorded: Map<Long, String>, settingsHash: String) {
        val json = JSONObject()
        for ((id, hash) in recorded) json.put(id.toString(), hash)
        // Se confirma SOLO después de completar la carga cifrada a Drive.
        prefs(c).edit().putString(BACKED_UP, json.toString())
            .putString(BACKED_UP_SETTINGS, settingsHash)
            .putLong("last_upload", System.currentTimeMillis())
            .putBoolean("needs_authorization", false).commit()
        invalidate()
    }

    fun markNeedsAuthorization(c: Context) {
        prefs(c).edit().putBoolean("needs_authorization", true).apply()
        invalidate()
    }

    fun needsAuthorization(c: Context): Boolean =
        prefs(c).getBoolean("needs_authorization", false)

    fun lastUpload(c: Context): Long = prefs(c).getLong("last_upload", 0L)

    fun invalidate() {
        revision.postValue(System.nanoTime())
    }
}
