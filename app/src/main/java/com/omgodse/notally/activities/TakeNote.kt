package com.omgodse.notally.activities

import android.graphics.Typeface
import android.net.Uri
import android.text.Spanned
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.content.res.ColorStateList
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.omgodse.notally.room.NotallyDatabase
import com.omgodse.notally.room.Folder
import com.omgodse.notally.security.RoseInternalLinks
import com.omgodse.notally.miscellaneous.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import android.text.style.CharacterStyle
import android.text.style.StrikethroughSpan
import android.text.style.BackgroundColorSpan
import android.text.style.SuggestionSpan
import com.omgodse.notally.view.InlineImageRefSpan
import com.omgodse.notally.view.InlineNoteImageSpan
import com.omgodse.notally.view.InlineNoteImages
import android.text.Selection
import android.content.Intent
import android.text.Spannable
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.util.Patterns
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.core.text.getSpans
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.omgodse.notally.LinkMovementMethod
import com.omgodse.notally.MarkerSpan
import com.omgodse.notally.R
import com.omgodse.notally.miscellaneous.add
import com.omgodse.notally.miscellaneous.setOnNextAction
import com.omgodse.notally.room.Type

class TakeNote : NotallyActivity(Type.NOTE) {
    // Las notas antiguas con imágenes en el cuerpo siguen siendo legibles.
    private var previousImageNames: Set<String>? = null

    private fun renderInlineImages() {
        val editable = binding.EnterBody.text ?: return
        val references = editable.getSpans<InlineImageRefSpan>(0, editable.length).toList()
        for (reference in references) {
            val start = editable.getSpanStart(reference)
            val end = editable.getSpanEnd(reference)
            if (start < 0 || end <= start) continue
            lifecycleScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    InlineNoteImages.load(application, reference.imageName, model.imageRoot)
                }
                if (editable.getSpanStart(reference) == start &&
                    editable.getSpanEnd(reference) == end) {
                    editable.removeSpan(reference)
                    editable.setSpan(loaded, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    binding.EnterBody.invalidate()
                }
            }
        }
    }


    override fun configureUI() {
        binding.EnterTitle.setOnNextAction {
            binding.EnterBody.requestFocus()
        }

        setupEditor()

        if (model.isNewNote) {
            binding.EnterBody.requestFocus()
        }
    }


    private fun setupEditor() {
        setupMovementMethod()
        binding.EnterBody.setLinkTextColor(0xFFF1C45B.toInt())
        // Los enlaces web tecleados se detectan sin modificar los vínculos
        // internos. No se añaden dependencias ni se consulta Internet.
        val autoLink = Runnable { detectWebLinks() }
        binding.EnterBody.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.EnterBody.removeCallbacks(autoLink)
                binding.EnterBody.postDelayed(autoLink, 350L)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.EnterBody.post(autoLink)
        renderInlineImages()
        // Compatibilidad de lectura y eliminación de referencias heredadas.
        model.images.observe(this) { images ->
            val names = images.map { it.name }.toSet()
            val removed = previousImageNames?.minus(names).orEmpty()
            previousImageNames = names
            if (removed.isNotEmpty()) {
                val editable = binding.EnterBody.text
                if (editable != null) {
                    val placed = editable.getSpans<InlineNoteImageSpan>(0, editable.length)
                        .filter { it.imageName in removed }
                        .map { editable.getSpanStart(it) to editable.getSpanEnd(it) } +
                        editable.getSpans<InlineImageRefSpan>(0, editable.length)
                            .filter { it.imageName in removed }
                            .map { editable.getSpanStart(it) to editable.getSpanEnd(it) }
                    placed.sortedByDescending { it.first }.forEach { (start, end) ->
                        if (start >= 0 && end > start && end <= editable.length) {
                            editable.delete(start, end)
                        }
                    }
                }
            }
        }

        binding.EnterBody.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false

            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                binding.EnterBody.isActionModeOn = true
                // Try block is there because this will crash on MiUI as Xiaomi has a broken ActionMode implementation
                try {
                    if (menu != null) {
                        menu.add(R.string.bold, 0) {
                            applySpan(StyleSpan(Typeface.BOLD)); mode?.finish()
                        }
                        menu.add(R.string.italic, 0) {
                            applySpan(StyleSpan(Typeface.ITALIC)); mode?.finish()
                        }
                        menu.add(R.string.monospace, 0) {
                            applySpan(TypefaceSpan("monospace")); mode?.finish()
                        }
                        menu.add(R.string.strikethrough, 0) {
                            applySpan(StrikethroughSpan()); mode?.finish()
                        }
                        menu.add(R.string.link_to_note, 0) {
                            val range = selectedRange(); mode?.finish()
                            if (range != null) showLinkPicker(range.first, range.second)
                        }
                        menu.add(R.string.link, 0) {
                            val range = selectedRange(); mode?.finish()
                            if (range != null) showWebLinkEditor(range.first, range.second)
                        }
                        menu.add(R.string.notas_highlight, 0) {
                            applySpan(BackgroundColorSpan(0x77F1C45B)); mode?.finish()
                        }
                        val range = selectedRange()
                        if (range != null &&
                            binding.EnterBody.text?.getSpans<SuggestionSpan>(range.first, range.second)
                                ?.isNotEmpty() == true) {
                            menu.add(R.string.notas_ignore_spelling, 0) {
                                val text = binding.EnterBody.text
                                text?.getSpans<SuggestionSpan>(range.first, range.second)
                                    ?.forEach(text::removeSpan)
                                mode?.finish()
                            }
                        }
                        menu.add(R.string.clear_formatting, 0) {
                            removeSpans()
                            mode?.finish()
                        }
                    }
                } catch (exception: Exception) {
                    exception.printStackTrace()
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode?) {
                binding.EnterBody.isActionModeOn = false
            }
        }
    }

    private fun setupMovementMethod() {
        binding.EnterBody.movementMethod = LinkMovementMethod { span ->
            val stored = span.url
            val fallback = binding.EnterBody.text?.let { text ->
                val from = text.getSpanStart(span)
                val to = text.getSpanEnd(span)
                if (from < 0 || to <= from) null
                else getURLFrom(text.substring(from, to))
            }
            val target = stored?.takeIf { it.isNotBlank() } ?: fallback
            if (target.isNullOrBlank()) return@LinkMovementMethod
            val internal = RoseInternalLinks.parse(target)
            if (target.startsWith("rose://") && (internal == null || internal.first != "note")) {
                Toast.makeText(this, R.string.link_target_missing, Toast.LENGTH_LONG).show()
                return@LinkMovementMethod
            }
            val uri = Uri.parse(target)
            if (internal == null && uri.scheme !in setOf("https", "http", "mailto", "tel")) {
                Toast.makeText(this, R.string.cant_open_link, Toast.LENGTH_LONG).show()
                return@LinkMovementMethod
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.open_link)
                .setMessage(if (internal == null) target else
                    getString(R.string.link_open_note))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.open_link) { _, _ ->
                    if (internal != null) {
                        lifecycleScope.launch {
                            // Guardar el Editable visible con todos sus enlaces
                            // antes de navegar a la nota de destino.
                            captureCurrentEditorBody()
                            model.saveNote()
                            val existing = withContext(Dispatchers.IO) {
                                NotallyDatabase.getDatabase(application).getBaseNoteDao().get(internal.second)
                            }
                            if (existing == null || existing.folder != Folder.NOTES) {
                                Toast.makeText(this@TakeNote, R.string.link_target_missing, Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            startActivity(Intent(this@TakeNote, TakeNote::class.java)
                                .putExtra(Constants.SelectedBaseNote, internal.second))
                        }
                    } else {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (_: Exception) {
                            Toast.makeText(this, R.string.cant_open_link, Toast.LENGTH_LONG).show()
                        }
                    }
                }.show()
        }
    }

    private fun selectedRange(): Pair<Int, Int>? {
        val from = binding.EnterBody.selectionStart
        val to = binding.EnterBody.selectionEnd
        if (from < 0 || to < 0) return null
        return minOf(from, to) to maxOf(from, to)
    }

    private fun installLink(start: Int, end: Int, title: String, target: String) {
        val text = binding.EnterBody.text ?: return
        if (start !in 0..text.length || end !in start..text.length) return
        val last = if (start == end) {
            text.insert(start, title)
            start + title.length
        } else end
        // Android puede incluir en getSpans() otro enlace que sólo toca el
        // borde de la selección. Eliminar ÚNICAMENTE solapamientos reales.
        // Así se pueden insertar vínculos consecutivos sin borrar los previos.
        text.getSpans<URLSpan>(start, last).toList()
            .filter { span ->
                text.getSpanStart(span) < last && text.getSpanEnd(span) > start
            }
            .forEach(text::removeSpan)
        if (last <= start) return
        text.setSpan(URLSpan(target), start, last, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.EnterBody.setSelection(last)
        markNoteEdited()
        captureCurrentEditorBody()
        // Persistir cada vínculo inmediatamente: otro enlace, navegación
        // o cierre inesperado ya no debería perder el destino anterior.
        lifecycleScope.launch {
            try {
                model.saveNote()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                android.util.Log.e("NotasLinks", "Unable to persist note link", error)
                Toast.makeText(this@TakeNote, R.string.something_went_wrong, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Enlaces con ID estable, independientes del nombre de la nota. */
    private fun showLinkPicker(start: Int, end: Int) {
        lifecycleScope.launch {
            val entries: List<Pair<Long, String>> = withContext(Dispatchers.IO) {
                val db = NotallyDatabase.getDatabase(application)
                db.getBaseNoteDao().getLinkIndex()
                    .filter { it.id > 0L && it.id != model.id }
                    .map { it.id to it.title.ifBlank { getString(R.string.untitled_note_link) } }
            }
            val density = resources.displayMetrics.density
            fun dp(n: Int) = (n * density).toInt()
            val column = LinearLayout(this@TakeNote).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(12))
            }
            val search = EditText(this@TakeNote).apply {
                hint = getString(R.string.search)
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT
                setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0)
                compoundDrawablePadding = dp(8)
                compoundDrawableTintList = ColorStateList.valueOf(0xFFF1C45B.toInt())
            }
            column.addView(search, LinearLayout.LayoutParams(-1, dp(52)))
            val scroll = ScrollView(this@TakeNote)
            val results = LinearLayout(this@TakeNote).apply {
                orientation = LinearLayout.VERTICAL
            }
            scroll.addView(results)
            column.addView(scroll, LinearLayout.LayoutParams(-1, dp(285)))
            val dialog = MaterialAlertDialogBuilder(this@TakeNote)
                .setTitle(R.string.link_to_note)
                .setView(column)
                .setNegativeButton(R.string.cancel, null)
                .create()
            fun render(query: String) {
                results.removeAllViews()
                val matching = entries.filter { (_, name) ->
                    name.contains(query, ignoreCase = true)
                }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })
                if (matching.isEmpty()) {
                    results.addView(TextView(this@TakeNote).apply {
                        setText(R.string.link_no_results)
                        setPadding(dp(12), dp(24), dp(12), dp(24))
                    })
                } else matching.take(150).forEach { (id, name) ->
                    val row = TextView(this@TakeNote).apply {
                        text = name
                        textSize = 15f
                        maxLines = 2
                        setPadding(dp(12), dp(14), dp(12), dp(14))
                        setCompoundDrawablesWithIntrinsicBounds(
                            android.R.drawable.ic_menu_edit, 0, 0, 0)
                        compoundDrawablePadding = dp(12)
                        background = android.graphics.drawable.RippleDrawable(
                            ColorStateList.valueOf(0x33F1C45B), null, null)
                        setOnClickListener {
                            dialog.dismiss()
                            installLink(start, end, name, RoseInternalLinks.note(id))
                        }
                    }
                    results.addView(row, LinearLayout.LayoutParams(-1, -2))
                }
            }
            render("")
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    render(s?.toString().orEmpty().trim())
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            dialog.show()
        }
    }

    override fun showIncomingLinks() {
        lifecycleScope.launch {
            val target = RoseInternalLinks.note(model.id)
            val sources = withContext(Dispatchers.IO) {
                NotallyDatabase.getDatabase(application).getBaseNoteDao()
                    .getLinkIndex().filter { note ->
                        note.id != model.id &&
                            note.spans.any { it.link && it.url == target }
                    }
            }
            if (sources.isEmpty()) {
                MaterialAlertDialogBuilder(this@TakeNote)
                    .setTitle(R.string.backlinks)
                    .setMessage(R.string.backlinks_empty)
                    .setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            val titles = sources.map { it.title.ifBlank {
                getString(R.string.untitled_note_link)
            } }.toTypedArray()
            MaterialAlertDialogBuilder(this@TakeNote)
                .setTitle(R.string.backlinks)
                .setItems(titles) { _, which ->
                    val linked = sources.getOrNull(which) ?: return@setItems
                    lifecycleScope.launch {
                        captureCurrentEditorBody()
                        model.saveNote()
                        startActivity(Intent(this@TakeNote, TakeNote::class.java)
                            .putExtra(Constants.SelectedBaseNote, linked.id))
                    }
                }
                .setNegativeButton(R.string.cancel, null).show()
        }
    }

    private fun showWebLinkEditor(start: Int, end: Int) {
        val selected = binding.EnterBody.text?.subSequence(start, end)?.toString().orEmpty()
        val input = EditText(this).apply {
            hint = "https://"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(if (selected.startsWith("https://") || selected.startsWith("http://"))
                selected else "")
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.link, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text?.toString()?.trim().orEmpty()
                val target = getURLFrom(raw)
                val uri = Uri.parse(target)
                if (uri.scheme !in setOf("https", "http", "mailto", "tel") ||
                    (uri.scheme in setOf("https", "http") && uri.host.isNullOrBlank()) ||
                    (uri.scheme in setOf("mailto", "tel") && uri.schemeSpecificPart.isNullOrBlank())) {
                    input.error = getString(R.string.invalid_link)
                } else {
                    installLink(start, end, selected.ifBlank { target }, target)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun detectWebLinks() {
        val editable = binding.EnterBody.text ?: return
        val value = editable.toString()
        if (value.length > 250_000) return // Evitar búsquedas costosas en notas enormes.
        val matcher = android.util.Patterns.WEB_URL.matcher(value)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val found = value.substring(start, end)
            if (found.length > 2048) continue
            if (editable.getSpans<URLSpan>(start, end).isNotEmpty()) continue
            val canonical = getURLFrom(found)
            if (Uri.parse(canonical).scheme !in setOf("https", "http")) continue
            editable.setSpan(URLSpan(canonical), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun removeSpans() {
        val selectionEnd = binding.EnterBody.selectionEnd
        val selectionStart = binding.EnterBody.selectionStart

        ifBothNotNullAndInvalid(selectionStart, selectionEnd) { start, end ->
            binding.EnterBody.text?.getSpans<CharacterStyle>(start, end)?.forEach { span ->
                if (span !is MarkerSpan &&
                    span !is InlineNoteImageSpan && span !is InlineImageRefSpan) {
                    binding.EnterBody.text?.removeSpan(span)
                }
            }
        }
    }

    private fun applySpan(span: Any) {
        val selectionEnd = binding.EnterBody.selectionEnd
        val selectionStart = binding.EnterBody.selectionStart

        ifBothNotNullAndInvalid(selectionStart, selectionEnd) { start, end ->
            binding.EnterBody.text?.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun ifBothNotNullAndInvalid(start: Int?, end: Int?, function: (start: Int, end: Int) -> Unit) {
        if (start != null && start != -1 && end != null && end != -1) {
            function.invoke(start, end)
        }
    }


    companion object {

        fun getURLFrom(text: String): String {
            return when {
                text.matches(Patterns.PHONE.toRegex()) -> "tel:$text"
                text.matches(Patterns.EMAIL_ADDRESS.toRegex()) -> "mailto:$text"
                text.matches(Patterns.DOMAIN_NAME.toRegex()) -> "https://$text"
                else -> text
            }
        }
    }
}