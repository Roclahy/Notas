package com.omgodse.notally.activities

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.graphics.Color
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

    override fun onResume() {
        super.onResume()
        if (PinSecurity.appEnabled(this) && !PinSecurity.appUnlocked) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val content = window.decorView as ViewGroup
            val overlay = View(this).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true
                elevation = 10000f
            }
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
    }
}
