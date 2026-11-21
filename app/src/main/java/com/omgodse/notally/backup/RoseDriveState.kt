package com.omgodse.notally.backup

import android.content.Context

/** Solo guarda estado de la opción; los tokens OAuth los administra Google Play Services. */
object RoseDriveState {
    private const val PREFS = "rose_google_drive"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(c: Context): Boolean = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean("enabled", enabled).apply()
    }
    fun markUploaded(c: Context) {
        prefs(c).edit().putLong("last_upload", System.currentTimeMillis())
            .putBoolean("needs_authorization", false).apply()
    }
    fun markNeedsAuthorization(c: Context) {
        prefs(c).edit().putBoolean("needs_authorization", true).apply()
    }
    fun needsAuthorization(c: Context): Boolean =
        prefs(c).getBoolean("needs_authorization", false)
    fun lastUpload(c: Context): Long = prefs(c).getLong("last_upload", 0L)
}
