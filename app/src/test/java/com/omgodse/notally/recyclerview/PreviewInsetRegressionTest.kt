package com.omgodse.notally.recyclerview

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Regression guard for the source-of-truth XML preview insets.
 * Parsing the layout directly avoids Robolectric inflation failures caused
 * by unrelated drawable/theme resources.
 */
class PreviewInsetRegressionTest {

    @Test fun textPreviewsKeepTheirCompactRightInset() {
        val layout = File("src/main/res/layout/recycler_base_note.xml")
        assertTrue("Preview layout must exist in the app module", layout.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val document = factory.newDocumentBuilder().parse(layout)
        val androidNs = "http://schemas.android.com/apk/res/android"
        val expectedIds = setOf(
            "@+id/Title", "@+id/Date", "@+id/Note",
            "@+id/LinearLayout", "@+id/LabelGroup", "@+id/Reminder",
            "@+id/LockedTitle"
        )
        val found = mutableSetOf<String>()
        val all = document.getElementsByTagName("*")
        for (index in 0 until all.length) {
            val element = all.item(index) as Element
            val id = element.getAttributeNS(androidNs, "id")
            if (id !in expectedIds) continue
            found += id
            assertEquals("Leading inset changed for $id", "12dp",
                element.getAttributeNS(androidNs, "paddingStart"))
            assertEquals("Excessive right inset regressed for $id", "4dp",
                element.getAttributeNS(androidNs, "paddingEnd"))
        }
        assertEquals("All preview components must be covered", expectedIds, found)
    }
}
