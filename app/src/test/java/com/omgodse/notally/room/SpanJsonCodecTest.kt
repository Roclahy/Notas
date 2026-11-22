package com.omgodse.notally.room

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpanJsonCodecTest {
    private fun link(start: Int, end: Int, id: Long) = SpanRepresentation(
        bold = false, link = true, italic = false, monospace = false,
        strikethrough = false, start = start, end = end,
        url = "rose://note/$id"
    )

    @Test fun linkWithoutImageNameWritesJSONObjectNotNull() {
        val json = Converters.spansToJSONArray(listOf(link(10, 17, 46)))
        assertEquals(1, json.length())
        assertTrue(json.get(0) is JSONObject)
        val restored = Converters.jsonToSpans(json.toString())
        assertEquals(1, restored.size)
        assertEquals("rose://note/46", restored.single().url)
        assertEquals(10, restored.single().start)
        assertEquals(17, restored.single().end)
    }

    @Test fun adjacentLinksAndFormattingRoundTrip() {
        val spans = listOf(
            link(0, 5, 46), link(6, 10, 47),
            SpanRepresentation(
                bold = true, link = false, italic = true,
                monospace = false, strikethrough = false, start = 0, end = 10
            )
        )
        val encoded = Converters.spansToJSONArray(spans).toString()
        val arr = JSONArray(encoded)
        assertEquals(3, arr.length())
        for (index in 0 until arr.length()) {
            assertTrue("Expected JSONObject at $index", arr.get(index) is JSONObject)
        }
        assertEquals(spans, Converters.jsonToSpans(encoded))
    }

    @Test fun unreadableOldNullMetadataDoesNotCrash() {
        assertEquals(emptyList<SpanRepresentation>(), Converters.jsonToSpans("[null]"))
    }

    @Test fun stringEncodedLegacyObjectsCanBeRecovered() {
        val objectJson = Converters.spansToJSONArray(listOf(link(2, 9, 48))).getJSONObject(0)
        val legacy = JSONArray().put(objectJson.toString()).toString()
        val recovered = Converters.jsonToSpans(legacy)
        assertEquals("rose://note/48", recovered.single().url)
    }
}
