package com.omgodse.notally.fragments

import androidx.recyclerview.widget.RecyclerView

/**
 * Recreate a fresh layout manager without changing the adapter's data.
 *
 * Never submit an empty list as part of a folder switch. ListAdapter uses an
 * asynchronous differ: an intermediate empty-list commit can race the next
 * folder's contents and leave the first card in an old span or far below the top.
 *
 * This also repairs an invalid grid anchor when returning from the note editor.
 */
internal object FolderGridSwitch {
    fun reset(
        recycler: RecyclerView,
        adapter: RecyclerView.Adapter<*>,
        createLayoutManager: () -> RecyclerView.LayoutManager
    ) {
        recycler.stopScroll()
        recycler.adapter = null
        recycler.layoutManager = null
        recycler.recycledViewPool.clear()
        recycler.layoutManager = createLayoutManager()
        recycler.adapter = adapter
        recycler.scrollToPosition(0)
        recycler.requestLayout()
    }
}
