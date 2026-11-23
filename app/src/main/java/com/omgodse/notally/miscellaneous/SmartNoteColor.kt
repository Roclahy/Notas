package com.omgodse.notally.miscellaneous

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.omgodse.notally.security.SecureMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedHashMap

/**
 * Color Inteligente: cuenta los píxeles de la PRIMERA fotografía adjunta.
 *
 * El histograma agrupa por bloques RGB de 8 unidades (5 bits por canal).
 * El bin con MÁS píxeles gana, sin ponderar la saturación o la luminosidad.
 * Para preservar un tono real de la foto, devuelve el RGB medio de ese bin,
 * no un color de la paleta predefinida ni un HSL con brillo fijo.
 *
 * La imagen se descifra únicamente en memoria y el análisis se ejecuta en IO.
 */
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
            val key = file.absolutePath + "|" + file.length() + "|" + file.lastModified()
            synchronized(cache) { cache[key] }?.let { return@withContext it }
            val result = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                SecureMedia.openDecrypted(app, file).use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                if (bounds.outWidth < 1 || bounds.outHeight < 1) return@withContext null
                var sampleSize = 1
                while (bounds.outWidth / sampleSize > 192 || bounds.outHeight / sampleSize > 192) {
                    sampleSize *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bitmap = SecureMedia.openDecrypted(app, file).use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                } ?: return@withContext null
                try {
                    mostFrequentColor(bitmap)
                } finally {
                    bitmap.recycle()
                }
            } catch (error: Exception) {
                android.util.Log.w("NotasSmartColor", "Could not sample image RGB", error)
                null
            }
            if (result != null) synchronized(cache) { cache[key] = result }
            result
        }

    internal fun mostFrequentColor(bitmap: Bitmap): Int? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val counts = IntArray(1 shl 15)
        val sumR = IntArray(1 shl 15)
        val sumG = IntArray(1 shl 15)
        val sumB = IntArray(1 shl 15)
        var winner = -1
        var maxCount = 0
        for (pixel in pixels) {
            if (Color.alpha(pixel) < 128) continue
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val bin = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            counts[bin] += 1
            sumR[bin] += r
            sumG[bin] += g
            sumB[bin] += b
            if (counts[bin] > maxCount) {
                winner = bin
                maxCount = counts[bin]
            }
        }
        if (winner < 0) return null
        return Color.rgb(
            sumR[winner] / maxCount,
            sumG[winner] / maxCount,
            sumB[winner] / maxCount
        )
    }

    /** Sin paletas predefinidas ni cambios de luminosidad según tema. */
    fun actualColor(dominant: Int): Int = ColorUtils.setAlphaComponent(dominant, 255)

    /** Contraste WCAG: escoger texto oscuro o blanco según el RGB real. */
    fun textColor(background: Int): Int {
        val white = Color.WHITE
        val dark = Color.rgb(19, 19, 21)
        return if (ColorUtils.calculateContrast(white, background) >=
                   ColorUtils.calculateContrast(dark, background)) white else dark
    }
}
