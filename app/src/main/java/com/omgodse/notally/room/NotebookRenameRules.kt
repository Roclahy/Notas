package com.omgodse.notally.room

/** Validation is local: folder names are encrypted at rest. */
object NotebookRenameRules {
    fun normalized(value: String): String? {
        val name = value.trim()
        return name.takeIf { it.isNotBlank() && it.length <= 80 }
    }

    fun isDuplicate(id: Long, name: String, existing: List<NotebookFolder>): Boolean =
        existing.any { it.id != id && it.name.equals(name, ignoreCase = true) }
}
