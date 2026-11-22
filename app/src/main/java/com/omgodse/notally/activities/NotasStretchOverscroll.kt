package com.omgodse.notally.activities

import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView

/**
 * Overscroll nativo: estira en Android 12+ aunque el contenido no tenga scroll.
 * Versiones anteriores mantienen el efecto de borde propio del sistema.
 * Las listas incrustadas en el editor dejan el gesto al contenedor principal.
 */
internal object NotasStretchOverscroll {
    fun install(view: View, nestedInScrollable: Boolean = false) {
        val scrollContainer = view is NestedScrollView ||
            view is ScrollView || view is HorizontalScrollView
        when {
            scrollContainer -> view.overScrollMode = View.OVER_SCROLL_ALWAYS
            view is RecyclerView && !nestedInScrollable ->
                view.overScrollMode = View.OVER_SCROLL_ALWAYS
            view is AbsListView && !nestedInScrollable ->
                view.overScrollMode = View.OVER_SCROLL_ALWAYS
        }
        if (view is ViewGroup) {
            val nested = nestedInScrollable || scrollContainer
            for (i in 0 until view.childCount) install(view.getChildAt(i), nested)
        }
    }
}
