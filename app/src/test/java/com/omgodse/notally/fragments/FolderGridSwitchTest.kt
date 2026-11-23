package com.omgodse.notally.fragments

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderGridSwitchTest {
    private class NoteAdapter : RecyclerView.Adapter<NoteHolder>() {
        var count = 0

        override fun getItemCount(): Int = count
        override fun getItemViewType(position: Int): Int =
            if (position % 6 == 0) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteHolder {
            val context = parent.context
            return NoteHolder(TextView(context).apply {
                layoutParams = StaggeredGridLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (viewType == 1) 74 else 140
                ).apply { isFullSpan = viewType == 1 }
            })
        }

        override fun onBindViewHolder(holder: NoteHolder, position: Int) {
            holder.itemView.layoutParams.height = if (getItemViewType(position) == 1) 74
                else 90 + (position % 5) * 55
        }

        fun show(size: Int) {
            count = size
            notifyDataSetChanged()
        }
    }

    private class NoteHolder(view: View) : RecyclerView.ViewHolder(view)

    @Test fun changingFolderDiscardsOldSpanManagerAndAdapterState() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val recycler = RecyclerView(activity).apply {
            isSaveEnabled = false
            itemAnimator = null
        }
        activity.setContentView(FrameLayout(activity).apply {
            addView(recycler, ViewGroup.LayoutParams(1080, 1500))
        })
        val notes = NoteAdapter()
        recycler.adapter = notes
        recycler.layoutManager = newManager()
        fun measureAndLayout() {
            recycler.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY))
            recycler.layout(0, 0, 1080, 1500)
        }
        notes.show(25)
        measureAndLayout()
        for (count in listOf(1, 9, 0, 18, 2, 32, 1, 0, 5, 1)) {
            recycler.scrollToPosition(15)
            val previous = recycler.layoutManager
            FolderGridSwitch.reset(recycler, notes, clearItems = {
                notes.show(0)
            }) { newManager() }
            assertNotSame(previous, recycler.layoutManager)
            assertSame(notes, recycler.adapter)
            notes.show(count)
            recycler.scrollToPosition(0)
            measureAndLayout()
            val lm = recycler.layoutManager as StaggeredGridLayoutManager
            if (count > 0) {
                val first = lm.findFirstVisibleItemPositions(null)
                assertTrue("Expected first item at top after switch: ${first.contentToString()}",
                    first.any { it == 0 })
            }
        }
    }

    private fun newManager() = StaggeredGridLayoutManager(
        2, RecyclerView.VERTICAL
    ).apply {
        gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS
    }
}
