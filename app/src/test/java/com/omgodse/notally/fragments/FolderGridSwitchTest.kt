package com.omgodse.notally.fragments

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

        // Position 0 must be a regular note, not a full-span section header.
        override fun getItemViewType(position: Int): Int =
            if (position > 0 && position % 6 == 0) 1 else 0

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

    private fun createRecycler(): Pair<RecyclerView, NoteAdapter> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val recycler = RecyclerView(activity).apply {
            isSaveEnabled = false
            itemAnimator = null
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(12, 12, 12, 0)
        }
        activity.setContentView(FrameLayout(activity).apply {
            addView(recycler, ViewGroup.LayoutParams(1080, 1500))
        })
        val adapter = NoteAdapter()
        recycler.layoutManager = newManager()
        recycler.adapter = adapter
        return recycler to adapter
    }

    private fun measureAndLayout(recycler: RecyclerView) {
        recycler.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY))
        recycler.layout(0, 0, 1080, 1500)
    }

    private fun assertFirstNoteAtTopLeft(recycler: RecyclerView) {
        val holder = recycler.findViewHolderForAdapterPosition(0)
        assertNotNull("The first note must be attached and visible", holder)
        val first = holder!!.itemView
        assertTrue("First note must be in left column; left=${first.left}",
            first.left < recycler.width / 2)
        assertEquals("First note must start at the list left inset",
            recycler.paddingLeft, first.left)
        assertTrue("First note must not be stranded near the bottom; top=${first.top}",
            first.top <= recycler.paddingTop + 4)
    }

    @Test fun switchingFoldersKeepsTheFirstNoteAtTopLeft() {
        val (recycler, notes) = createRecycler()
        notes.show(25)
        measureAndLayout(recycler)

        for (count in listOf(1, 9, 0, 18, 2, 32, 1, 0, 5, 1)) {
            recycler.scrollToPosition(15)
            val previous = recycler.layoutManager
            FolderGridSwitch.reset(recycler, notes) { newManager() }
            assertNotSame(previous, recycler.layoutManager)
            assertSame(notes, recycler.adapter)
            notes.show(count)
            measureAndLayout(recycler)

            if (count > 0) {
                val manager = recycler.layoutManager as StaggeredGridLayoutManager
                val visible = manager.findFirstVisibleItemPositions(null)
                assertTrue("Expected first item visible, positions=${visible.contentToString()}",
                    visible.any { it == 0 })
                assertFirstNoteAtTopLeft(recycler)
            }
        }
    }

    @Test fun returningFromEditorClearsAnOldScrollAndSpanAnchor() {
        val (recycler, notes) = createRecycler()
        notes.show(30)
        measureAndLayout(recycler)
        recycler.scrollToPosition(24)
        measureAndLayout(recycler)

        // Reuse the same data source as the activity does after saving a note.
        // The reset must work even when changing folders did not occur.
        notes.show(1)
        FolderGridSwitch.reset(recycler, notes) { newManager() }
        assertEquals("Reset must not insert an intermediate empty adapter data set",
            1, notes.itemCount)
        measureAndLayout(recycler)
        assertFirstNoteAtTopLeft(recycler)
    }

    @Test fun resettingLayoutDoesNotClearTheCurrentNoteList() {
        val (recycler, notes) = createRecycler()
        notes.show(5)
        measureAndLayout(recycler)
        FolderGridSwitch.reset(recycler, notes) { newManager() }
        assertEquals(5, notes.itemCount)
        measureAndLayout(recycler)
        assertFirstNoteAtTopLeft(recycler)
    }

    private fun newManager() = StaggeredGridLayoutManager(
        2, RecyclerView.VERTICAL
    ).apply {
        gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS
    }
}
