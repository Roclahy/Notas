package com.omgodse.notally

import android.app.Application
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.URLSpan
import android.view.View
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omgodse.notally.activities.TakeNote
import com.omgodse.notally.miscellaneous.Constants
import com.omgodse.notally.room.NotallyDatabase
import com.omgodse.notally.room.Type
import com.omgodse.notally.security.RoseInternalLinks
import com.omgodse.notally.viewmodels.NotallyModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteLinksPersistenceTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val dao get() = NotallyDatabase.getDatabase(app).getBaseNoteDao()

    private fun create(title: String, body: String): Long = runBlocking {
        val model = NotallyModel(app)
        model.type = Type.NOTE
        model.title = title
        model.body = SpannableStringBuilder(body)
        model.saveNote()
    }

    @Test fun spansRoundTripThroughEncryptedRoomAndModel() {
        val targetA = create("target A", "target content A")
        val targetB = create("target B", "target content B")
        val model = NotallyModel(app)
        model.type = Type.NOTE
        model.title = "source span test"
        val content = SpannableStringBuilder("Alpha Beta")
        content.setSpan(URLSpan(RoseInternalLinks.note(targetA)), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        content.setSpan(URLSpan(RoseInternalLinks.note(targetB)), 6, 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        model.body = content
        val id = runBlocking { model.saveNote() }
        try {
            val persisted = requireNotNull(dao.get(id))
            val urls = persisted.spans.filter { it.link }.mapNotNull { it.url }.toSet()
            assertEquals(setOf(RoseInternalLinks.note(targetA), RoseInternalLinks.note(targetB)), urls)
            val reloaded = NotallyModel(app)
            runBlocking { reloaded.setState(id) }
            val spans = reloaded.body.getSpans(0, reloaded.body.length, URLSpan::class.java)
            assertEquals(setOf(RoseInternalLinks.note(targetA), RoseInternalLinks.note(targetB)),
                spans.map { it.url }.toSet())
        } finally {
            runBlocking { dao.delete(longArrayOf(id, targetA, targetB)) }
        }
    }

    @Test fun editorLinkInstallSurvivesFinishAndReopen() {
        val targetA = create("target A editor", "target content A")
        val targetB = create("target B editor", "target content B")
        val source = create("source editor test", "Alpha Beta")
        try {
            val intent = Intent(app, TakeNote::class.java)
                .putExtra(Constants.SelectedBaseNote, source)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val scenario = ActivityScenario.launch<TakeNote>(intent)
            try {
                var installed = false
                for (attempt in 0 until 75) {
                    scenario.onActivity { activity ->
                        val view = activity.findViewById<View>(R.id.ScrollView)
                        val editor = activity.findViewById<EditText>(R.id.EnterBody)
                        if (!installed && view?.visibility == View.VISIBLE &&
                            editor?.text?.toString() == "Alpha Beta") {
                            val install = TakeNote::class.java.getDeclaredMethod(
                                "installLink",
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType,
                                String::class.java,
                                String::class.java
                            )
                            install.isAccessible = true
                            install.invoke(activity, 0, 5, "Alpha", RoseInternalLinks.note(targetA))
                            install.invoke(activity, 6, 10, "Beta", RoseInternalLinks.note(targetB))
                            val inEditor = editor.text.getSpans(0, editor.text.length, URLSpan::class.java)
                            assertEquals(2, inEditor.size)
                            installed = true
                            activity.finish()
                        }
                    }
                    if (installed) break
                    Thread.sleep(100)
                }
                assertTrue("Editor failed to load", installed)
                var saved = false
                for (attempt in 0 until 100) {
                    val urls = dao.get(source)?.spans.orEmpty().filter { it.link }.mapNotNull { it.url }.toSet()
                    if (urls == setOf(RoseInternalLinks.note(targetA), RoseInternalLinks.note(targetB))) {
                        saved = true
                        break
                    }
                    Thread.sleep(100)
                }
                assertTrue("Editor links were not saved after finish", saved)
            } finally { scenario.close() }
            val reopened = ActivityScenario.launch<TakeNote>(intent)
            try {
                var loaded = false
                for (attempt in 0 until 75) {
                    reopened.onActivity { activity ->
                        val view = activity.findViewById<View>(R.id.ScrollView)
                        val editor = activity.findViewById<EditText>(R.id.EnterBody)
                        if (view?.visibility == View.VISIBLE && editor?.text?.toString() == "Alpha Beta") {
                            val spans = editor.text.getSpans(0, editor.text.length, URLSpan::class.java)
                            assertEquals(setOf(RoseInternalLinks.note(targetA), RoseInternalLinks.note(targetB)),
                                spans.map { it.url }.toSet())
                            loaded = true
                        }
                    }
                    if (loaded) break
                    Thread.sleep(100)
                }
                assertTrue("Editor failed to reload", loaded)
            } finally { reopened.close() }
        } finally {
            runBlocking { dao.delete(longArrayOf(source, targetA, targetB)) }
        }
    }
}
