package com.omgodse.notally.security

import android.net.Uri

/**
 * Enlaces internos con IDs en lugar de títulos: sobrevivir a cambios de nombre.
 * Las referencias a notas se reescriben al importar una copia de Drive porque
 * la restauración asigna nuevos IDs numéricos.
 */
object RoseInternalLinks {
    private const val SCHEME = "rose"
    fun note(id: Long): String = "rose://note/$id"
    fun folder(id: Long): String = "rose://folder/$id"

    fun parse(url: String?): Pair<String, Long>? {
        val value = url ?: return null
        if (value.length > 100) return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if (uri.scheme != SCHEME || uri.query != null || uri.fragment != null ||
            uri.port != -1 || uri.userInfo != null) return null
        val type = uri.host ?: return null
        if (type != "note" && type != "folder") return null
        val segments = uri.pathSegments
        if (segments.size != 1) return null
        val id = segments[0].toLongOrNull()?.takeIf { it > 0L } ?: return null
        return type to id
    }

    fun remap(url: String?, notes: Map<Long, Long>, folders: Map<Long, Long>): String? {
        val original = url ?: return null
        val (type, id) = parse(original) ?: return original
        val current = if (type == "note") notes[id] else folders[id]
        // Si el destino faltó en el respaldo, mantener roto el vínculo; no
        // apuntar accidentalmente a otra nota que reutilice el antiguo ID.
        return if (current != null) {
            if (type == "note") note(current) else folder(current)
        } else "rose://missing/$id"
    }
}
