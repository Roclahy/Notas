package com.omgodse.notally.view

import android.animation.ValueAnimator
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import com.omgodse.notally.preferences.Preferences

/**
 * Material de interfaz, no un fondo gris/azul. El cristal solo se usa como
 * capa flotante (navegación y hojas); Ajustes y notas conservan su superficie.
 *
 * En Android 13+ LiquidLensBackdrop refracta contenido real con AGSL.
 * En versiones anteriores, el material se degrada a una cápsula translúcida.
 */
object LiquidGlass {
    enum class Role { NAVIGATION, GROUP, CARD, SHEET, METADATA, ACCENT }

    fun enabled(context: Context): Boolean =
        Preferences.getInstance(context.applicationContext as Application).isGlassTheme()

    private fun night(context: Context): Boolean {
        val p = Preferences.getInstance(context.applicationContext as Application)
        return p.isAmoledTheme() ||
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun highContrast(context: Context) = runCatching {
        Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) != 0
    }.getOrDefault(false)

    fun surface(context: Context, role: Role, backdrop: Int? = null): Drawable {
        val dark = backdrop?.let { ColorUtils.calculateLuminance(it) < 0.5 } ?: night(context)
        val solid = when {
            role == Role.ACCENT -> 0xFFF1C45B.toInt()
            highContrast(context) && dark -> 0xFF242428.toInt()
            highContrast(context) -> 0xFFF5F5F7.toInt()
            role == Role.GROUP || role == Role.CARD ->
                if (dark) 0xFF1C1C1E.toInt() else Color.WHITE
            role == Role.METADATA ->
                if (dark) 0x770F1014 else 0x99FFFFFF.toInt()
            role == Role.NAVIGATION ->
                if (dark) 0xAF17191E.toInt() else 0xCFE9EDF2.toInt()
            else -> if (dark) 0xE025272D.toInt() else 0xECF7F8FB.toInt()
        }
        val radius = when (role) {
            Role.NAVIGATION, Role.METADATA, Role.ACCENT -> 999f
            Role.SHEET -> 30f
            Role.GROUP, Role.CARD -> 18f
        } * context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(solid)
            if (role != Role.GROUP && role != Role.CARD) {
                setStroke((context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    if (dark) 0x5BFFFFFF else 0xABFFFFFF.toInt())
            }
        }
    }

    fun attachBackdrop(target: View, behind: View) {
        if (!enabled(target.context)) return
        if (Build.VERSION.SDK_INT >= 33 && !highContrast(target.context)) {
            target.background = LiquidLensBackdrop(target, behind)
        } else {
            target.background = surface(target.context, Role.NAVIGATION)
        }
    }

    /** Una tarjeta de contenido no se convierte en lente: es el documento. */
    fun styleCard(card: MaterialCardView, context: Context, color: Int? = null) {
        if (!enabled(context)) return
        val bg = color ?: if (night(context)) 0xFF1C1C1E.toInt() else Color.WHITE
        card.setCardBackgroundColor(bg)
        card.strokeColor = if (night(context)) 0x323F424B else 0x26A9B0BC
        card.strokeWidth = (context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        card.cardElevation = 0f
    }

    fun styleDialog(dialog: Dialog, context: Context) {
        if (!enabled(context)) return
        val window = dialog.window ?: return
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.2f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(WindowManager::class.java)
            if (manager?.isCrossWindowBlurEnabled == true) {
                window.setBackgroundBlurRadius((24f * context.resources.displayMetrics.density).toInt())
            }
        }
    }

    fun animatePress(view: View, down: Boolean) {
        view.animate().cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.animate().scaleX(if (down) 0.973f else 1f)
            .scaleY(if (down) 0.973f else 1f)
            .setDuration(if (down) 110L else 230L)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
    }
}
