package com.omgodse.notally.miscellaneous

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.omgodse.notally.preferences.Preferences
import com.omgodse.notally.security.SecureMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedHashMap

/** El primer adjunto determina el color. No se escribe imagen descifrada a disco. */
object SmartNoteColor {
    private val cache = object : LinkedHashMap<String, Int>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =
            size > 48
    }

    suspend fun dominant(app: Application, root: File?, filename: String?): Int? =
        withContext(Dispatchers.IO) {
            if (root == null || filename == null ||
                !filename.matches(Regex("[a-zA-Z0-9_.-]{1,160}"))) return@withContext null
            val file = File(root, filename)
            if (!file.isFile) return@withContext null
            val key = file.absolutePath + "|" + file.length() + "|" + file.lastModified()
            synchronized(cache) { cache[key] }?.let { return@withContext it }
            val sampled = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                SecureMedia.openDecrypted(app, file).use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth < 1 || bounds.outHeight < 1) return@withContext null
                var sample = 1
                while (bounds.outWidth / sample > 192 || bounds.outHeight / sample > 192) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = SecureMedia.openDecrypted(app, file).use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: return@withContext null
                try {
                    Palette.from(bitmap).maximumColorCount(16).generate()
                        .dominantSwatch?.rgb
                } finally {
                    bitmap.recycle()
                }
            } catch (error: Exception) {
                android.util.Log.w("NotasSmartColor", "Unable to sample attached image", error)
                null
            }
            if (sampled != null) synchronized(cache) { cache[key] = sampled }
            sampled
        }

    /** Se conserva el matiz predominante, con claridad adaptada al tema
     * para mantener el contraste del texto en Claro/Oscuro/AMOLED. */
    fun forTheme(dominant: Int, context: Context): Int {
        val amoled = Preferences.getInstance(context.applicationContext as Application).isAmoledTheme()
        val night = amoled ||
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(dominant, hsl)
        hsl[1] = hsl[1].coerceIn(0.15f, 0.70f)
        hsl[2] = if (night) 0.22f else 0.87f
        return ColorUtils.HSLToColor(hsl)
    }
}
