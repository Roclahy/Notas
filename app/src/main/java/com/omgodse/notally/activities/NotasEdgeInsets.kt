package com.omgodse.notally.activities

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/**
 * Insets de barra de estado, barras de navegación, recortes e IME.
 * Sin tamaño fijo añadido: el contenido y sus controles no quedan tapados.
 */
internal object NotasEdgeInsets {
    fun install(root: View, includeKeyboard: Boolean) {
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = if (includeKeyboard && insets.isVisible(WindowInsetsCompat.Type.ime())) {
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            } else 0
            view.setPadding(
                left + bars.left,
                top + bars.top,
                right + bars.right,
                bottom + max(bars.bottom, ime))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
