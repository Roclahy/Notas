package com.omgodse.notally.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.omgodse.notally.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device crash/ANR report. Never stores note bodies, titles, URLs,
 * attachments, exception messages, intent data, identifiers or logcat.
 * Sharing is always initiated manually by the user.
 */
object NotasErrorReport {
    private const val NAME = "Notas-Reporte.txt"
    private const val MAX_BYTES = 128 * 1024L
    private val lock = Any()
    @Volatile private var foreground = false
    @Volatile private var lastPulseMs = 0L
    @Volatile private var stallReported = false
    @Volatile private var installed = false
    private val clock = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    private val identifier = Regex("[^a-zA-Z0-9_.$-]")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainThread = Looper.getMainLooper().thread

    fun file(app: Application): File =
        File(File(app.filesDir, "logs").apply { mkdirs() }, NAME)

    fun exists(app: Application) = file(app).let { it.exists() && it.length() > 0L }

    fun read(app: Application): String = try {
        synchronized(lock) { if (exists(app)) file(app).readText(Charsets.UTF_8) else "" }
    } catch (_: Exception) { "" }

    fun delete(app: Application) {
        synchronized(lock) { file(app).delete() }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        lastPulseMs = SystemClock.elapsedRealtime()
        stallReported = false
    }

    fun install(app: Application) {
        synchronized(lock) {
            if (installed) return
            installed = true
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { record(app, "CRASH", thread, throwable) } catch (_: Throwable) {}
            if (previous != null) previous.uncaughtException(thread, throwable)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        try { collectSystemExitReasons(app) } catch (_: Throwable) {}
        val power = app.getSystemService(PowerManager::class.java)
        Thread({
            while (true) {
                try {
                    if (foreground) {
                        mainHandler.post {
                            lastPulseMs = SystemClock.elapsedRealtime()
                            stallReported = false
                        }
                        Thread.sleep(2000)
                        val delay = SystemClock.elapsedRealtime() - lastPulseMs
                        if (foreground && delay >= 8000 && !stallReported &&
                            !Debug.isDebuggerConnected() && power?.isInteractive != false) {
                            stallReported = true
                            record(app, "POSSIBLE_ANR", mainThread, null)
                        }
                    } else Thread.sleep(2000)
                } catch (_: Throwable) {
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                }
            }
        }, "Notas-ANR-watchdog").apply { isDaemon = true; start() }
    }

    // Android 11+: official ANR/native crash exit reasons on next app launch.
    // Do not read raw system traces or descriptions (may contain user data).
    private fun collectSystemExitReasons(app: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val preferences = app.getSharedPreferences("notas_diagnostic_exits", 0)
        val lastSeen = preferences.getLong("last_timestamp", 0L)
        val manager = app.getSystemService(ActivityManager::class.java) ?: return
        var newest = lastSeen
        for (exit in manager.getHistoricalProcessExitReasons(null, 0, 15).sortedBy { it.timestamp }) {
            if (exit.timestamp <= lastSeen) continue
            newest = maxOf(newest, exit.timestamp)
            val kind = when (exit.reason) {
                android.app.ApplicationExitInfo.REASON_ANR -> "SYSTEM_ANR"
                android.app.ApplicationExitInfo.REASON_CRASH -> "SYSTEM_CRASH"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                else -> null
            } ?: continue
            record(app, kind, null, null,
                "Exit timestamp: ${exit.timestamp}; reason code: ${exit.reason}; status: ${exit.status}")
        }
        if (newest > lastSeen) preferences.edit().putLong("last_timestamp", newest).apply()
    }

    private fun clean(value: String) = value.replace(identifier, "_").take(160)

    // Static source locations only; no Throwable.message, toString or logcat.
    fun safeStack(frames: Array<StackTraceElement>, limit: Int = 48): String =
        frames.take(limit).joinToString("\n") { frame ->
            "  at ${clean(frame.className)}.${clean(frame.methodName)}(" +
                "${clean(frame.fileName ?: "Unknown")}:${frame.lineNumber})"
        }

    private fun record(
        app: Application, type: String, thread: Thread?, error: Throwable?,
        details: String = ""
    ) {
        val runtime = Runtime.getRuntime()
        val memory = ActivityManager.MemoryInfo()
        try { app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory) }
        catch (_: Throwable) {}
        val report = buildString {
            append("\n========== Notas error ==========\n")
            synchronized(clock) { append("Date: ").append(clock.format(Date())).append('\n') }
            append("Type: ").append(type).append('\n')
            append("App version: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Android: ").append(Build.VERSION.RELEASE)
                .append(" API ").append(Build.VERSION.SDK_INT).append('\n')
            append("Device: ").append(clean(Build.MANUFACTURER)).append(" / ")
                .append(clean(Build.MODEL)).append('\n')
            append("Uptime ms: ").append(SystemClock.elapsedRealtime()).append('\n')
            append("Foreground: ").append(foreground).append('\n')
            append("Heap used/max: ").append(runtime.totalMemory() - runtime.freeMemory())
                .append('/').append(runtime.maxMemory()).append('\n')
            append("RAM available: ").append(memory.availMem).append('\n')
            append("Low memory: ").append(memory.lowMemory).append('\n')
            if (details.isNotBlank()) append(details).append('\n')
            if (thread != null) {
                append("Main thread: ").append(thread === mainThread).append('\n')
                append("Thread state: ").append(thread.state.name).append('\n')
                append("Thread stack:\n").append(safeStack(thread.stackTrace)).append('\n')
            }
            var cause = error
            var depth = 0
            while (cause != null && depth < 4) {
                append("Exception level ").append(depth).append(": ")
                    .append(clean(cause.javaClass.name)).append('\n')
                append("Exception stack:\n").append(safeStack(cause.stackTrace)).append('\n')
                cause = cause.cause
                depth++
            }
        }
        try {
            synchronized(lock) {
                val destination = file(app)
                if (destination.length() + report.toByteArray(Charsets.UTF_8).size > MAX_BYTES)
                    destination.delete()
                FileOutputStream(destination, true).use {
                    it.write(report.toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
            }
        } catch (_: Throwable) { /* Diagnostics cannot change app behavior. */ }
    }
}
