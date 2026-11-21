package com.omgodse.notally.activities

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/**
 * Ajusta las áreas interactivas sin abandonar edge-to-edge.
 *
 * Para Inicio, root es DrawerLayout y content es su RelativeLayout central.
 * El DrawerLayout conserva su tamaño de ventana completa; el cajón de
 * navegación gestiona sus propios insets. Para otras pantallas ambos son
 * la misma vista. Esto evita mezclar padding del cajón con la cabecera.
 */
internal object NotasEdgeInsets {
    fun install(root: View, content: View = root, includeKeyboard: Boolean) {
        // Capturar los valores XML solo una vez, nunca el padding ya incrementado.
        val left = content.paddingLeft
        val top = content.paddingTop
        val right = content.paddingRight
        val bottom = content.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = if (includeKeyboard &&
                insets.isVisible(WindowInsetsCompat.Type.ime())) {
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            } else 0

            // Usar valores originales evita que cada nueva entrega de insets
            // acumule altura. El teclado sustituye, no suma, la barra inferior.
            content.setPadding(
                left + bars.left,
                top + bars.top,
                right + bars.right,
                bottom + max(bars.bottom, keyboard)
            )
            // No consumir: el NavigationView y otros hijos pueden necesitar
            // sus propios insets.
            insets
        }

        // onContentChanged ocurre antes del attach en algunas Activity.
        // Solicitar insets solo tras attach evita perder la primera entrega.
        if (ViewCompat.isAttachedToWindow(root)) {
            ViewCompat.requestApplyInsets(root)
        } else {
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    ViewCompat.requestApplyInsets(v)
                }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }
}
