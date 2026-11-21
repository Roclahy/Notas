package com.omgodse.notally.activities

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.appcompat.content.res.AppCompatResources
import com.omgodse.notally.R
import kotlin.math.min

/**
 * Splash overlay 100% nativo, sin GIF ni WebView dentro del APK.
 * Conserva la forma exacta del icono de Rose. Una sola animación de apertura.
 */
internal class RoseSplashView(activity: Activity) : View(activity) {
    private val parent = activity.window.decorView as ViewGroup
    private val logo = requireNotNull(AppCompatResources.getDrawable(activity, R.drawable.rose_logo)).mutate()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private var progress = 0f

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height * 0.46f
        val side = min(width, height) * 0.67f
        val half = side / 2f
        val left = (centerX - half).toInt()
        val top = (centerY - half).toInt()
        logo.bounds = Rect(left, top, (centerX + half).toInt(), (centerY + half).toInt())
        val reveal = (progress / 0.70f).coerceIn(0f, 1f)
        val smooth = reveal * reveal * (3f - 2f * reveal)
        canvas.save()
        // Del tallo hacia los pétalos, sin rebotes ni efecto de latido.
        canvas.clipRect(left.toFloat(), top + (side * (1f - smooth)), left + side, top + side)
        logo.draw(canvas)
        canvas.restore()
        val wordAlpha = ((progress - 0.65f) / 0.25f).coerceIn(0f, 1f)
        paint.alpha = (255 * wordAlpha).toInt()
        canvas.drawText(context.getString(R.string.app_name), centerX, top + side + 24f * resources.displayMetrics.density, paint)
    }

    private fun animateEntrance() {
        val scaleSetting = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        } catch (_: Exception) { 1f }
        if (scaleSetting == 0f) {
            parent.removeView(this)
            return
        }
        ValueAnimator.ofFloat(0f,1f).apply {
            duration = 950L
            interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
            addUpdateListener {
                progress = it.animatedValue as Float
                alpha = if (progress > 0.89f) ((1f-progress)/0.11f).coerceIn(0f,1f) else 1f
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (parent.indexOfChild(this@RoseSplashView) >= 0) parent.removeView(this@RoseSplashView)
                }
            })
            start()
        }
    }

    companion object {
        fun show(activity: Activity) {
            val splash = RoseSplashView(activity)
            splash.parent.addView(splash, ViewGroup.LayoutParams(-1,-1))
            splash.post { splash.animateEntrance() }
        }
    }
}
