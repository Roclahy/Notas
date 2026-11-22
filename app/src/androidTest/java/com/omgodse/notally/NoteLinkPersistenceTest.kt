package com.omgodse.notally

import android.app.Application
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.URLSpan
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.omgodse.notally.activities.TakeNote
import com.omgodse.notally.miscellaneous.Constants
import com.omgodse.notally.room.BaseNote
import com.omgodse.notally.room.Color
import com.omgodse.notally.room.Folder
import com.omgodse.notally.room.NotallyDatabase
import com.omgodse.notally.room.Type
import com.omgodse.notally.security.RoseInternalLinks
import com.omgodse.notally.viewmodels.NotallyModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs with a real Room SQLite database, Android Spannables and AndroidKeyStore. */
@RunWith(AndroidJUnit4::class)
class NoteLinkPersistenceTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val dao get() = NotallyDatabase.getDatabase(app).getBaseNoteDao()

    private fun makeNote(title: String, content: String = "Alpha Beta"): Long = runBlocking {
        dao.insert(
            BaseNote(
                id = 0L, type = Type.NOTE, folder = Folder.NOTES,
                color = Color.DEFAULT, title = title, pinned = false,
                timestamp = System.currentTimeMillis(), labels = emptyList(),
                body = content, spans = emptyList(), items = emptyList(),
                images = emptyList(), audios = emptyList(), reminder = null
            )
        )
    }

    private fun urls(spans: Spanned): List<String> =
        spans.getSpans(0, spans.length, URLSpan::class.java).map { it.url }.sorted()

    private fun storedUrls(id: Long): List<String> =
        requireNotNull(dao.get(id)).spans.filter { it.link }.mapNotNull { it.url }.sorted()

    @Test
    fun modelRoundTripPersistsTwoDistinctLinksAfterReopen() {
        val a = makeNote("Destination A")
        val b = makeNote("Destination B")
        val source = makeNote("Link source")
        try {
            val links = SpannableStringBuilder("Alpha Beta")
            links.setSpan(URLSpan(RoseInternalLinks.note(a)), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            links.setSpan(URLSpan(RoseInternalLinks.note(b)), 6, 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            runBlocking {
                val model = NotallyModel(app)
                model.type = Type.NOTE
                model.setState(source)
                model.body = links
                model.saveNote()
            }
            val expected = listOf(RoseInternalLinks.note(a), RoseInternalLinks.note(b)).sorted()
            assertEquals("DB write lost internal URL metadata", expected, storedUrls(source))
            runBlocking {
                val reopened = NotallyModel(app)
                reopened.type = Type.NOTE
                reopened.setState(source)
                assertEquals("DB read lost internal URLSpan", expected, urls(reopened.body))
            }
        } finally {
            runBlocking { dao.delete(longArrayOf(a, b, source)) }
        }
    }

    @Test
    fun editorLinkActionSurvivesExitAndRelaunch() {
        val a = makeNote("Destination C")
        val b = makeNote("Destination D")
        val source = makeNote("Editor source")
        val expected = listOf(RoseInternalLinks.note(a), RoseInternalLinks.note(b)).sorted()
        try {
            val intent = Intent(app, TakeNote::class.java)
                .putExtra(Constants.SelectedBaseNote, source)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<TakeNote>(intent).use { scenario ->
                var loaded = false
                repeat(30) {
                    scenario.onActivity { activity ->
                        loaded = activity.findViewById<EditText>(R.id.EnterBody).text.toString() == "Alpha Beta"
                    }
                    if (loaded) return@repeat
                    Thread.sleep(100)
                }
                assertTrue("Editor failed to load its note", loaded)
                scenario.onActivity { activity ->
                    val editor = activity.findViewById<EditText>(R.id.EnterBody)
                    val linkAction = TakeNote::class.java.getDeclaredMethod(
                        "installLink", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        String::class.java, String::class.java
                    ).apply { isAccessible = true }
                    linkAction.invoke(activity, 0, 5, "Alpha", RoseInternalLinks.note(a))
                    linkAction.invoke(activity, 6, 10, "Beta", RoseInternalLinks.note(b))
                    assertEquals("Link selection did not decorate editor", expected, urls(editor.text))
                    activity.finish()
                }
            }
            val stored = storedUrls(source)
            assertEquals("Exit did not commit both links to Room", expected, stored)
            ActivityScenario.launch<TakeNote>(intent).use { scenario ->
                var urlsAfterReload = emptyList<String>()
                repeat(30) {
                    scenario.onActivity { activity ->
                        val editor = activity.findViewById<EditText>(R.id.EnterBody)
                        urlsAfterReload = urls(editor.text)
                    }
                    if (urlsAfterReload == expected) return@repeat
                    Thread.sleep(100)
                }
                assertEquals("Reopened editor lost linked notes", expected, urlsAfterReload)
            }
        } finally {
            runBlocking { dao.delete(longArrayOf(a, b, source)) }
        }
    }
}
