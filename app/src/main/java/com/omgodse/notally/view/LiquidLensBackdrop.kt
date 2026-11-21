package com.omgodse.notally.view

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import kotlin.math.max

/**
 * Lente GPU Android 13+ (AGSL) sin librerías externas.
 *
 * Captura un área pequeña y temporal de la vista HERMANA situada detrás
 * de la barra; nunca captura el cristal mismo, para evitar realimentación.
 * No realiza screenshots de pantalla completa ni escribe datos en disco.
 * La captura se reduce al 50% y limita las actualizaciones a 12 fps.
 */
@RequiresApi(33)
internal class LiquidLensBackdrop(
    private val target: View,
    private val behind: View
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val surface = RectF()
    private val density = target.resources.displayMetrics.density
    private val dark = (target.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val fallback = LiquidGlass.surface(target.context, LiquidGlass.Role.NAVIGATION)
    private var snapshot: Bitmap? = null
    private var shader: RuntimeShader? = runCatching { RuntimeShader(AGSL) }.getOrNull()
    private var lastCapture = 0L
    private var observer: ViewTreeObserver? = null
    private var opacity = 255

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val now = SystemClock.uptimeMillis()
        if (target.isShown && behind.isShown && now - lastCapture >= 85L) {
            lastCapture = now
            updateBackdrop()
        }
        true
    }

    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { register() }
        override fun onViewDetachedFromWindow(v: View) { unregister() }
    }

    init {
        target.addOnAttachStateChangeListener(attachment)
        if (target.isAttachedToWindow) register()
    }

    private fun register() {
        val tree = target.viewTreeObserver
        if (tree.isAlive && observer !== tree) {
            unregister()
            observer = tree
            tree.addOnPreDrawListener(preDraw)
        }
    }

    private fun unregister() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observer = null
        snapshot?.recycle()
        snapshot = null
    }

    private fun updateBackdrop() {
        val w = target.width
        val h = target.height
        if (w < 2 || h < 2 || !behind.isAttachedToWindow) return
        val bw = max(1, w / 2)
        val bh = max(1, h / 2)
        try {
            var bitmap = snapshot
            if (bitmap == null || bitmap.isRecycled || bitmap.width != bw || bitmap.height != bh) {
                bitmap?.recycle()
                bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                snapshot = bitmap
            } else {
                bitmap.eraseColor(Color.TRANSPARENT)
            }
            val locationBehind = IntArray(2)
            val locationTarget = IntArray(2)
            behind.getLocationInWindow(locationBehind)
            target.getLocationInWindow(locationTarget)
            val capture = Canvas(bitmap)
            capture.scale(bw / w.toFloat(), bh / h.toFloat())
            capture.translate(
                (locationBehind[0] - locationTarget[0]).toFloat(),
                (locationBehind[1] - locationTarget[1]).toFloat())
            // Only the underlying document/fragment, never the glass overlay.
            behind.draw(capture)
            val input = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            input.setLocalMatrix(Matrix().apply {
                setScale(w / bw.toFloat(), h / bh.toFloat())
            })
            shader?.apply {
                setInputShader("content", input)
                setFloatUniform("size", w.toFloat(), h.toFloat())
                setFloatUniform("radius", 30f * density)
                setFloatUniform("night", if (dark) 1f else 0f)
            }
            invalidateSelf()
        } catch (_: RuntimeException) {
            // Algunos widgets hardware no pueden dibujarse en un Canvas software.
            // No arriesgar un cierre: conservar la última muestra válida.
        }
    }

    override fun draw(canvas: Canvas) {
        val effect = shader
        val bmp = snapshot
        if (effect == null || bmp == null || bmp.isRecycled ||
            bounds.width() < 2 || bounds.height() < 2) {
            fallback.bounds = bounds
            fallback.draw(canvas)
            return
        }
        surface.set(bounds)
        val radius = minOf(30f * density, surface.height() * 0.5f)
        paint.shader = effect
        paint.alpha = opacity
        canvas.drawRoundRect(surface, radius, radius, paint)
        paint.shader = null

        val inset = 0.6f * density
        surface.inset(inset, inset)
        border.strokeWidth = 1.2f * density
        border.color = if (dark) 0x64FFFFFF else 0xD0FFFFFF.toInt()
        border.alpha = opacity
        canvas.drawRoundRect(surface, radius - inset, radius - inset, border)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, 30f * density)
    }

    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        // Pequeño shader AGSL. La lente distorsiona el contenido cerca del borde,
        // separa ligeramente RGB y aplica un velo variable para contraste.
        private const val AGSL = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float night;

half4 main(float2 p) {
    float2 center = size * 0.5;
    float2 edgeBox = abs(p - center) - (center - float2(radius));
    float dist = length(max(edgeBox, float2(0.0))) +
        min(max(edgeBox.x, edgeBox.y), 0.0) - radius;
    float2 direction = (p - center) / max(length(p - center), 0.01);
    float lens = (1.0 - smoothstep(2.0, 19.0, abs(dist))) * 6.0;
    float2 position = clamp(p + direction * lens, float2(0.0), size - 1.0);
    half3 centerSample = content.eval(position).rgb;
    half3 neighboring = (
        content.eval(clamp(position + float2(-2.0, -1.5), float2(0.0), size - 1.0)).rgb +
        content.eval(clamp(position + float2(2.0, 1.5), float2(0.0), size - 1.0)).rgb +
        content.eval(clamp(position + float2(-1.5, 2.0), float2(0.0), size - 1.0)).rgb +
        content.eval(clamp(position + float2(1.5, -2.0), float2(0.0), size - 1.0)).rgb
    ) * 0.25;
    half3 soft = mix(centerSample, neighboring, 0.54);
    half3 dispersed = half3(
        content.eval(clamp(position + direction * 1.1, float2(0.0), size - 1.0)).r,
        soft.g,
        content.eval(clamp(position - direction * 1.1, float2(0.0), size - 1.0)).b
    );
    float rim = (1.0 - smoothstep(0.0, 4.0, abs(dist)));
    float upper = pow(clamp(1.0 - p.y / size.y, 0.0, 1.0), 2.3);
    half3 tint = mix(half3(0.985, 0.99, 1.0), half3(0.11, 0.12, 0.14), night);
    half3 glass = mix(dispersed, tint, mix(0.23, 0.42, night));
    glass += half3(1.0) * (rim * (0.15 + upper * 0.24));
    return half4(clamp(glass, half3(0.0), half3(1.0)), 0.96);
}
"""
    }
}
