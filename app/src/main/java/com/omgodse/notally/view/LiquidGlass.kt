package com.omgodse.notally.view

import android.animation.ValueAnimator
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import com.omgodse.notally.preferences.Preferences

/**
 * Material decorativo de Rose: inspirado en el comportamiento de Liquid Glass.
 *
 * A diferencia del material privado de Apple, Android no expone un shader
 * de refracción del fondo para cada View. Este Drawable proporciona
 * capas translúcidas, borde de iluminación variable, volumen y respuesta
 * al tacto sin leer píxeles de las notas (privacidad y rendimiento).
 * Los diálogos pueden aprovechar el desenfoque de ventana de Android 12+.
 */
object LiquidGlass {
    enum class Role { NAVIGATION, GROUP, CARD, SHEET, METADATA, ACCENT }

    fun enabled(context: Context): Boolean =
        Preferences.getInstance(context.applicationContext as Application).isGlassTheme()

    private fun amoled(context: Context): Boolean =
        Preferences.getInstance(context.applicationContext as Application).isAmoledTheme()

    private fun night(context: Context): Boolean =
        amoled(context) ||
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    private data class Palette(
        val top: Int,
        val middle: Int,
        val bottom: Int,
        val rim: Int,
        val refraction: Int,
        val underside: Int
    )

    private fun palette(context: Context, role: Role, backdrop: Int?): Palette {
        val isNight = night(context)
        val isAmoled = amoled(context)
        val onDark = backdrop?.let {
            ColorUtils.calculateLuminance(it) < 0.48
        } ?: isNight
        val strong = role == Role.NAVIGATION || role == Role.SHEET
        if (role == Role.ACCENT) {
            return Palette(0xFFFCE2A6.toInt(), 0xF7F1C45B.toInt(),
                0xF2C99D37.toInt(), 0xE6FFFFFF.toInt(),
                0x74FFFFFF, 0x40A66F16)
        }
        if (!onDark) {
            val opacity = if (strong) 224 else 190
            return Palette(ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), opacity),
                ColorUtils.setAlphaComponent(0xFFF7F8FB.toInt(), opacity - 24),
                ColorUtils.setAlphaComponent(0xFFE7EAF1.toInt(), opacity - 30),
                0xDEFFFFFF.toInt(), 0x85FFFFFF.toInt(),
                0x24313F59)
        }
        val opacity = if (strong) 206 else if (isAmoled) 185 else 174
        return Palette(ColorUtils.setAlphaComponent(0xFF414651.toInt(), opacity),
            ColorUtils.setAlphaComponent(0xFF2C313C.toInt(), opacity - 19),
            ColorUtils.setAlphaComponent(0xFF191C25.toInt(), opacity - 25),
            0x80FFFFFF.toInt(), 0x62FFFFFF,
            0x382D3648)
    }

    fun surface(context: Context, role: Role, backdrop: Int? = null): Drawable =
        GlassDrawable(context, role, palette(context, role, backdrop))

    /**
     * CardView requiere su propio MaterialShapeDrawable. Aplicar aquí color y
     * borde translúcidos conserva clip de fotos, selección y esquinas propias.
     */
    fun styleCard(card: MaterialCardView, context: Context) {
        val p = palette(context, Role.CARD, null)
        card.setCardBackgroundColor(p.middle)
        card.strokeColor = p.rim
        card.strokeWidth = dp(context, 1.0f).toInt().coerceAtLeast(1)
        card.cardElevation = dp(context, 2f)
    }

    fun styleDialog(dialog: Dialog, context: Context) {
        if (!enabled(context)) return
        val window = dialog.window ?: return
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.18f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(WindowManager::class.java)
            if (manager?.isCrossWindowBlurEnabled == true) {
                // Desenfoca lo que hay DETRÁS del diálogo; nunca su texto.
                window.setBackgroundBlurRadius(dp(context, 36f).toInt())
            }
        }
    }

    fun animatePress(view: View, down: Boolean) {
        view.animate().cancel()
        val animationsEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            ValueAnimator.areAnimatorsEnabled()
        if (!animationsEnabled) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.animate()
            .scaleX(if (down) 0.968f else 1f)
            .scaleY(if (down) 0.968f else 1f)
            .setDuration(if (down) 110L else 230L)
            .setInterpolator(DecelerateInterpolator(1.7f))
            .start()
    }

    private fun dp(context: Context, v: Float): Float =
        context.resources.displayMetrics.density * v

    private class GlassDrawable(
        private val context: Context,
        private val role: Role,
        private val palette: Palette
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val path = Path()
        private var transparency = 255
        private var isPressed = false

        private fun corner(): Float = dp(context, when (role) {
            Role.NAVIGATION -> 30f
            Role.SHEET -> 28f
            Role.GROUP -> 22f
            Role.CARD -> 22f
            Role.METADATA, Role.ACCENT -> 999f
        })

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            rect.set(bounds)
            val radius = corner().coerceAtMost(rect.height() / 2f)
            paint.reset()
            paint.isAntiAlias = true
            paint.alpha = transparency
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(rect.left, rect.top,
                rect.right, rect.bottom,
                intArrayOf(palette.top, palette.middle, palette.bottom),
                floatArrayOf(0f, 0.47f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, radius, radius, paint)

            canvas.save()
            path.reset()
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.clipPath(path)
            // Reflexión superior y refracción oblicua suaves, sin ruido ni destellos continuos.
            paint.shader = LinearGradient(rect.left, rect.top, rect.right,
                rect.top + rect.height() * 0.68f,
                intArrayOf(palette.refraction, 0x17FFFFFF, Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP)
            canvas.drawRect(rect.left, rect.top, rect.right,
                rect.top + rect.height() * 0.68f, paint)

            paint.shader = LinearGradient(rect.left, rect.bottom - rect.height() * 0.34f,
                rect.right, rect.bottom,
                palette.underside, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(rect.left, rect.bottom - rect.height() * 0.34f,
                rect.right, rect.bottom, paint)
            canvas.restore()

            // Contorno especular: cara iluminada superior/izquierda y base tenue.
            paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(palette.rim, 0x26FFFFFF, palette.underside),
                floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(context, if (role == Role.METADATA) 0.85f else 1.2f)
            val inset = paint.strokeWidth / 2f + 0.25f
            rect.inset(inset, inset)
            canvas.drawRoundRect(rect, (radius - inset).coerceAtLeast(0f),
                (radius - inset).coerceAtLeast(0f), paint)

            if (isPressed) {
                paint.shader = null
                paint.style = Paint.Style.FILL
                paint.color = if (role == Role.ACCENT) 0x20FFFFFF else 0x1FFFFFFF
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            paint.shader = null
        }

        override fun onStateChange(state: IntArray): Boolean {
            val next = state.contains(android.R.attr.state_pressed)
            if (next == isPressed) return false
            isPressed = next
            invalidateSelf()
            return true
        }

        override fun isStateful(): Boolean = true
        override fun getOutline(outline: Outline) {
            val r = corner().coerceAtMost(bounds.height() / 2f)
            outline.setRoundRect(bounds, r)
        }
        override fun setAlpha(alpha: Int) {
            transparency = alpha.coerceIn(0, 255)
            invalidateSelf()
        }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
