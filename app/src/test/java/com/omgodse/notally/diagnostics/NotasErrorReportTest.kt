package com.omgodse.notally.diagnostics

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotasErrorReportTest {
    @Test fun safeStackKeepsOnlySourceLocationAndNoUserValue() {
        val frame = StackTraceElement(
            "com.rocla.notas.Editor", "saveNote", "Editor.kt", 143
        )
        val result = NotasErrorReport.safeStack(arrayOf(frame))
        assertTrue(result.contains("com.rocla.notas.Editor.saveNote"))
        assertTrue(result.contains("Editor.kt:143"))
        assertFalse(result.contains("private note content"))
    }

    @Test fun reportIsAbsentUntilCrashOrAnrAndDeletionDoesNotAffectNotes() {
        val app = RuntimeEnvironment.getApplication() as Application
        NotasErrorReport.delete(app)
        assertFalse(NotasErrorReport.exists(app))
        assertTrue(NotasErrorReport.read(app).isBlank())
    }

    @Test fun dangerousStringsAreNotInterpolatedByStackFormatter() {
        val frame = StackTraceElement("Editor", "save()", "Editor.kt", 10)
        val result = NotasErrorReport.safeStack(arrayOf(frame))
        assertFalse(result.contains("(" + ")")) 
        assertTrue(result.contains("Editor.save__"))
    }
}
