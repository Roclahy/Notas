package com.omgodse.notally.miscellaneous

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartNoteColorRegressionTest {
    @Test fun retainsRealPhotoRGBRatherThanProjectingIntoPalette() {
        val image = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.rgb(43, 117, 167))
            image.setPixel(0, 0, Color.rgb(235, 50, 25))
            val actual = SmartNoteColor.mostFrequentColor(image)
            assertEquals(Color.rgb(43, 117, 167), actual)
            assertEquals(actual, SmartNoteColor.actualColor(requireNotNull(actual)))
            assertNotEquals(Color.rgb(39, 66, 85), actual) // Not preset dark Storm.
        } finally { image.recycle() }
    }

    @Test fun darkAndBrightPhotoColorsUseReadableForegrounds() {
        val dark = Color.rgb(20, 45, 88)
        val bright = Color.rgb(247, 225, 180)
        assertEquals(Color.WHITE, SmartNoteColor.textColor(dark))
        assertEquals(Color.rgb(19, 19, 21), SmartNoteColor.textColor(bright))
        assertNotEquals(dark, bright)
    }

    @Test fun preservesOriginalLuminanceAndSaturation() {
        val light = Color.rgb(240, 182, 48)
        val deep = Color.rgb(29, 53, 130)
        assertEquals(light, SmartNoteColor.actualColor(light))
        assertEquals(deep, SmartNoteColor.actualColor(deep))
    }
}
