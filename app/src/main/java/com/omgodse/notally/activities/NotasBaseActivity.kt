package com.omgodse.notally.activities

import android.os.Bundle
import android.os.Build
import android.content.res.Configuration
import androidx.core.view.WindowCompat
import android.view.View
import android.view.ViewGroup
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.content.res.ColorStateList
import android.view.WindowManager
import com.omgodse.notally.security.PinSecurity
import com.omgodse.notally.security.PinDialogs
import androidx.appcompat.app.AppCompatActivity
import com.omgodse.notally.R
import com.omgodse.notally.preferences.Preferences

/**
 * Mantiene la selección AMOLED independiente del tema oscuro normal.
 * Se ejecuta antes de inflar las vistas de cada Activity.
 */
open class NotasBaseActivity : AppCompatActivity() {
    private var pinOverlay: View? = null

    protected open fun onAppAccessGranted() {}

    override fun onContentChanged() {
        super.onContentChanged()
        // El DrawerLayout de Inicio ocupa toda la ventana (también detrás
        // de las barras). Solo su contenido principal necesita la zona segura:
        // dar padding al DrawerLayout entero desplazaba también el cajón y
        // podía dejar la cabecera debajo de las notificaciones en Android 14.
        val container = findViewById<ViewGroup>(android.R.id.content)
        container?.getChildAt(0)?.let { root ->
            val safeContent = if (this is MainActivity) {
                root.findViewById<View>(R.id.RelativeLayout) ?: root
            } else root
            NotasEdgeInsets.install(
                root = root,
                content = safeContent,
                includeKeyboard = this is NotallyActivity
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (PinSecurity.appEnabled(this) && !PinSecurity.appUnlocked) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val content = window.decorView as ViewGroup
            val density = resources.displayMetrics.density
            fun dp(v: Int) = (density * v).toInt()
            val overlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this@NotasBaseActivity, android.R.attr.colorBackground, Color.BLACK))
                isClickable = true
                elevation = 10000f
            }
            val lock = ImageView(this).apply {
                setImageResource(R.drawable.notas_lock)
                imageTintList = ColorStateList.valueOf(0xFFF1C45B.toInt())
            }
            overlay.addView(lock, LinearLayout.LayoutParams(dp(64),dp(64)))
            val name = TextView(this).apply {
                text = getString(R.string.internal_app_name)
                gravity = Gravity.CENTER
                textSize = 32f
                setTextColor(com.google.android.material.color.MaterialColors.getColor(this@NotasBaseActivity, com.google.android.material.R.attr.colorOnSurface, Color.WHITE))
                setPadding(0,dp(20),0,dp(10))
            }
            overlay.addView(name)
            val helper = TextView(this).apply {
                setText(R.string.lock_screen_hint)
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(0xFFF1C45B.toInt())
            }
            overlay.addView(helper)
            content.addView(overlay, ViewGroup.LayoutParams(-1, -1))
            pinOverlay = overlay
            PinDialogs.check(this, "pin:app", onVerified = {
                PinSecurity.appUnlocked = true
                content.removeView(overlay)
                pinOverlay = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                onAppAccessGranted()
            }, onCancel = { finish() })
        } else {
            onAppAccessGranted()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Preferences.getInstance(application).isAmoledTheme()) {
            setTheme(R.style.AppThemeAmoled)
        }
        super.onCreate(savedInstanceState)
        // Edge-to-edge también en Android 7-14: dibujo por detrás de las
        // barras del sistema; cada pantalla aplicará sus propios insets.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val light = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}
