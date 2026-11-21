package com.omgodse.notally.security

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max

/**
 * Importador compartido de fondos: notas y carpetas.
 * Decodifica URI de galerías/documentos (incluido HEIF Android 9+), reduce
 * tamaño y cifra inmediatamente mediante Keystore antes de persistir.
 */
object NoteWallpaperStore {
    private const val maxSide = 2400

    fun import(app: Application, uri: Uri): String {
        val bitmap = decode(app, uri)
        val bytes = try {
            val stream = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)) {
                throw IOException("Unable to encode wallpaper")
            }
            stream.toByteArray()
        } finally {
            bitmap.recycle()
        }
        val dir = File(app.filesDir, "SecureMedia/Wallpapers")
            .apply { check(isDirectory || mkdirs()) }
        val name = "${UUID.randomUUID()}.wall"
        val output = File(dir, name)
        try {
            ByteArrayInputStream(bytes).use { source ->
                SecureMedia.encryptFromStream(app, source, output)
            }
            // Leer hasta el tag GCM para garantizar que el fichero final es válido.
            SecureMedia.openDecrypted(app, output).use { verified ->
                val buffer = ByteArray(32768)
                var read = 0L
                while (true) {
                    val n = verified.read(buffer)
                    if (n < 0) break
                    read += n
                }
                check(read == bytes.size.toLong()) { "Encrypted wallpaper incomplete" }
            }
            return name
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            bytes.fill(0)
        }
    }

    private fun decode(app: Application, uri: Uri): Bitmap {
        val resolver = app.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(resolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val size = info.size
                val larger = max(size.width, size.height)
                require(size.width > 0 && size.height > 0 && larger > 0) {
                    "Invalid image dimensions"
                }
                if (larger > maxSide) {
                    val factor = maxSide.toFloat() / larger
                    decoder.setTargetSize(
                        max(1, (size.width * factor).toInt()),
                        max(1, (size.height * factor).toInt()))
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image format" }
        val options = BitmapFactory.Options()
        while (max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > maxSide) {
            options.inSampleSize *= 2
        }
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("Could not read selected image")
    }
}
