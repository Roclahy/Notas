package com.omgodse.notally.fragments

import androidx.recyclerview.widget.RecyclerView

/**
 * A folder switch is a data-set boundary, not a normal diff animation.
 *
 * A recycled staggered-grid child can still reference its previous span while
 * DiffUtil and gap handling are processing the new folder. Detach the old
 * adapter and manager before clearing the old list, then attach a fresh
 * manager with no span history. Never invalidate live span assignments after
 * an asynchronous diff callback.
 */
internal object FolderGridSwitch {
    fun reset(
        recycler: RecyclerView,
        adapter: RecyclerView.Adapter<*>,
        clearItems: () -> Unit,
        createLayoutManager: () -> RecyclerView.LayoutManager
    ) {
        recycler.stopScroll()
        recycler.adapter = null
        clearItems()
        recycler.layoutManager = createLayoutManager()
        recycler.recycledViewPool.clear()
        recycler.adapter = adapter
    }
}
