package com.omgodse.notally.view

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ImageSpan
import com.omgodse.notally.R
import com.omgodse.notally.security.SecureMedia
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** A retained reference used while the secure thumbnail is being loaded. */
class InlineImageRefSpan(val imageName: String) : CharacterStyle() {
    override fun updateDrawState(tp: TextPaint) = Unit
}

/** References the same encrypted image as the note's attachment list. */
class InlineNoteImageSpan(val imageName: String, drawable: Drawable) :
    ImageSpan(drawable, ALIGN_BOTTOM)

object InlineNoteImages {
    /** Called on Dispatchers.IO; media plaintext is never written to disk. */
    fun load(app: Application, name: String, root: File?): InlineNoteImageSpan {
        val density = app.resources.displayMetrics.density
        val maxWidth = (app.resources.displayMetrics.widthPixels - 48 * density).toInt().coerceAtLeast(120)
        val maxHeight = (280 * density).toInt()
        val file = root?.let { File(it, name) }
        val bitmap = try {
            require(name.matches(Regex("[a-zA-Z0-9_.-]{1,160}")))
            requireNotNull(file)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            SecureMedia.openDecrypted(app, file).use { BitmapFactory.decodeStream(it, null, opts) }
            require(opts.outWidth > 0 && opts.outHeight > 0)
            var sample = 1
            while (opts.outWidth / sample > maxWidth * 2 ||
                opts.outHeight / sample > maxHeight * 2) sample *= 2
            val decoded = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = SecureMedia.openDecrypted(app, file).use {
                BitmapFactory.decodeStream(it, null, decoded)
            } ?: throw java.io.IOException("Image cannot be decoded")
            val ratio = min(1f, min(maxWidth.toFloat() / raw.width,
                maxHeight.toFloat() / raw.height))
            val width = max(1, (raw.width * ratio).toInt())
            val height = max(1, (raw.height * ratio).toInt())
            if (width == raw.width && height == raw.height) raw
            else Bitmap.createScaledBitmap(raw, width, height, true).also { raw.recycle() }
        } catch (_: Exception) {
            val width = min(maxWidth, (260 * density).toInt())
            val height = (68 * density).toInt().coerceAtLeast(44)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                val canvas = Canvas(this)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = 0x33222222
                canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(),
                    12 * density, 12 * density, paint)
                paint.color = 0xFFF1C45B.toInt()
                paint.textSize = 12f * density
                val label = app.getString(R.string.notas_missing_inline_image)
                canvas.drawText(label, 12 * density, height / 2f + 4 * density, paint)
            }
        }
        val drawable = BitmapDrawable(app.resources, bitmap).apply {
            setBounds(0, 0, bitmap.width, bitmap.height)
        }
        return InlineNoteImageSpan(name, drawable)
    }
}
