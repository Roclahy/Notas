package com.omgodse.notally.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookRenameRulesTest {
    @Test fun acceptsAndTrimsNewNames() {
        assertEquals("Personal", NotebookRenameRules.normalized("  Personal  "))
        assertEquals("Work", NotebookRenameRules.normalized("Work"))
    }

    @Test fun rejectsBlankAndOverlongNames() {
        assertNull(NotebookRenameRules.normalized("   "))
        assertNull(NotebookRenameRules.normalized("a".repeat(81)))
        assertEquals("a".repeat(80), NotebookRenameRules.normalized("a".repeat(80)))
    }

    @Test fun preventsCaseInsensitiveDuplicatesWithoutBlockingSameFolder() {
        val folders = listOf(
            NotebookFolder(id = 14L, name = "Personal", passwordHash = "secret", wallpaperName = "image.wall"),
            NotebookFolder(id = 15L, name = "Trabajo")
        )
        assertTrue(NotebookRenameRules.isDuplicate(15L, "personal", folders))
        assertFalse(NotebookRenameRules.isDuplicate(14L, "Personal", folders))
        assertFalse(NotebookRenameRules.isDuplicate(14L, "PERSONAL", folders))
        assertFalse(NotebookRenameRules.isDuplicate(14L, "Nuevo", folders))
    }
}
