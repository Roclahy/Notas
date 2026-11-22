package com.omgodse.notally.diagnostics

import android.content.Context
import com.omgodse.notally.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only diagnostic log for internal note links.
 *
 * This intentionally NEVER writes titles, note bodies, selected text, URLs,
 * exception messages, SQL values, auth tokens, or key material.
 * The only note identifiers included are numeric local Room IDs.
 * Nothing is uploaded automatically. Sharing requires an explicit user tap.
 */
object NoteLinkTrace {
    private const val MAX_LOG_BYTES = 128 * 1024
    private val lock = Any()
    private val safeChars = Regex("[^a-zA-Z0-9_.,:;= /\\-]")
    private val clock = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    }

    private fun directory(context: Context): File =
        File(context.filesDir, "logs").also { it.mkdirs() }

    private fun current(context: Context) = File(directory(context), "notas-enlaces.log")
    private fun previous(context: Context) = File(directory(context), "notas-enlaces.log.1")

    fun record(context: Context, event: String, detail: String = "", error: Throwable? = null) {
        try {
            val time = clock.get()!!.format(Date())
            val tag = event.replace(safeChars, "_").take(64)
            val values = detail.replace(safeChars, "_").take(350)
            val thread = Thread.currentThread().name.replace(safeChars, "_").take(32)
            val trace = StringBuilder()
                .append(time).append(" v").append(BuildConfig.VERSION_NAME)
                .append(" event=").append(tag)
                .append(" thread=").append(thread)
            if (values.isNotBlank()) trace.append(" ").append(values)
            if (error != null) {
                var cause: Throwable? = error
                var depth = 0
                while (cause != null && depth < 4) {
                    // Exception messages may contain private note content.
                    trace.append(" exception[").append(depth).append("]=")
                        .append(cause.javaClass.name)
                    cause.stackTrace.take(12).forEach { frame ->
                        trace.append("\n  at ")
                            .append(frame.className.replace(safeChars, "_")).append(".")
                            .append(frame.methodName.replace(safeChars, "_"))
                            .append("(").append(frame.fileName?.replace(safeChars, "_") ?: "?")
                            .append(":").append(frame.lineNumber).append(")")
                    }
                    cause = cause.cause
                    depth++
                }
            }
            trace.append("\n")
            synchronized(lock) {
                val file = current(context)
                if (file.length() + trace.length * 2 > MAX_LOG_BYTES) {
                    val archive = previous(context)
                    if (archive.exists()) archive.delete()
                    if (!file.renameTo(archive)) file.delete()
                }
                file.appendText(trace.toString(), Charsets.UTF_8)
            }
        } catch (_: Exception) {
            // Diagnostics must never crash the notes app or prevent saving.
        }
    }

    fun read(context: Context): String = synchronized(lock) {
        buildString {
            val old = previous(context)
            if (old.exists()) append(old.readText(Charsets.UTF_8))
            val file = current(context)
            if (file.exists()) append(file.readText(Charsets.UTF_8))
        }
    }

    fun export(context: Context): File = synchronized(lock) {
        val destination = File(context.cacheDir, "exported").apply { mkdirs() }
        File(destination, "Notas-registro-enlaces.txt").apply {
            writeText(read(context).ifBlank { "No link events recorded.\n" }, Charsets.UTF_8)
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        current(context).delete()
        previous(context).delete()
        File(File(context.cacheDir, "exported"), "Notas-registro-enlaces.txt").delete()
    }
}
