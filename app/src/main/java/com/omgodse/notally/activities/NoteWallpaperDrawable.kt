package com.omgodse.notally.activities

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max

/** Fondo edge-to-edge con recorte central y degradado superior de legibilidad. */
class NoteWallpaperDrawable(
    private val bitmap: Bitmap,
    private val darkText: Boolean
) : Drawable() {
    private val picture = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val gradient = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        if (bitmap.isRecycled) return
        val dst = bounds
        if (dst.width() <= 0 || dst.height() <= 0) return
        val scale = max(dst.width().toFloat() / bitmap.width,
            dst.height().toFloat() / bitmap.height)
        val cropW = (dst.width() / scale).toInt().coerceAtMost(bitmap.width)
        val cropH = (dst.height() / scale).toInt().coerceAtMost(bitmap.height)
        val source = Rect((bitmap.width - cropW) / 2, (bitmap.height - cropH) / 2,
            (bitmap.width + cropW) / 2, (bitmap.height + cropH) / 2)
        canvas.drawBitmap(bitmap, source, dst, picture)

        // Velo general para leer cuerpo y título, sin ocultar la fotografía.
        canvas.save()
        canvas.clipRect(dst)
        canvas.drawColor(if (darkText) 0x55FFFFFF else 0x67000000)
        val top = if (darkText) 0xD9FFFFFF.toInt() else 0xD9000000.toInt()
        gradient.shader = LinearGradient(0f, dst.top.toFloat(), 0f,
            dst.top + dst.height() * 0.48f,
            intArrayOf(top, Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        canvas.drawRect(dst, gradient)
        canvas.restore()
        gradient.shader = null
    }

    override fun setAlpha(alpha: Int) { picture.alpha = alpha }
    @Deprecated("Deprecated in Java")
    override fun setColorFilter(colorFilter: ColorFilter?) {
        picture.colorFilter = colorFilter
    }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        fun average(bitmap: Bitmap): Int {
            var r = 0L; var g = 0L; var b = 0L; var count = 0L
            val stepX = (bitmap.width / 20).coerceAtLeast(1)
            val stepY = (bitmap.height / 20).coerceAtLeast(1)
            for (y in 0 until bitmap.height step stepY) {
                for (x in 0 until bitmap.width step stepX) {
                    val pixel = bitmap.getPixel(x, y)
                    r += Color.red(pixel); g += Color.green(pixel)
                    b += Color.blue(pixel); count++
                }
            }
            return Color.rgb((r / count).toInt(), (g / count).toInt(),
                (b / count).toInt())
        }
    }
}
