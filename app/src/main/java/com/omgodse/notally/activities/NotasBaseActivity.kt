package com.omgodse.notally.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.omgodse.notally.R
import com.omgodse.notally.preferences.Preferences

/**
 * Mantiene la selección AMOLED independiente del tema oscuro normal.
 * Se ejecuta antes de inflar las vistas de cada Activity.
 */
open class NotasBaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Preferences.getInstance(application).isAmoledTheme()) {
            setTheme(R.style.AppThemeAmoled)
        }
        super.onCreate(savedInstanceState)
    }
}
