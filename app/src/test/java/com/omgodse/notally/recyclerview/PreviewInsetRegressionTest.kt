package com.omgodse.notally.recyclerview

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import com.google.android.material.card.MaterialCardView
import com.omgodse.notally.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for excessive right-side preview margins reported in 1.4.26
 * and made worse in 1.4.27 by increasing the end inset from 12 to 16dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewInsetRegressionTest {

    @Test fun textPreviewsUseACompactRightInsetWithoutShrinkingTheLeftInset() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        val card = LayoutInflater.from(activity).inflate(
            R.layout.recycler_base_note, FrameLayout(activity), false
        ) as MaterialCardView
        val density = activity.resources.displayMetrics.density
        fun px(dp: Int): Int = (dp * density + 0.5f).toInt()
        val ids = listOf(
            R.id.Title, R.id.Date, R.id.Note,
            R.id.LinearLayout, R.id.LabelGroup, R.id.Reminder, R.id.LockedTitle
        )
        for (id in ids) {
            val child = card.findViewById<View>(id)
            assertEquals("Unexpected leading inset for ${activity.resources.getResourceEntryName(id)}",
                px(12), ViewCompat.getPaddingStart(child))
            assertEquals("Right-side preview padding regressed for ${activity.resources.getResourceEntryName(id)}",
                px(4), ViewCompat.getPaddingEnd(child))
        }
    }
}
