package com.omgodse.notally.backup

import android.content.Context
import android.preference.PreferenceManager

/** Media selection is opt-in for unattended uploads. All backups remain AES-GCM encrypted. */
object BackupMediaPolicy {
    const val INCLUDE_AUTO_IMAGES = "notas_auto_include_images_v1"
    fun includeAutomaticImages(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(INCLUDE_AUTO_IMAGES, false)

    fun setAutomaticImages(context: Context, include: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(INCLUDE_AUTO_IMAGES, include).apply()
    }
}
