package com.omgodse.notally.recyclerview.viewholder

import android.graphics.drawable.Drawable
import android.app.Application
import android.view.ViewGroup
import com.omgodse.notally.preferences.Preferences
import com.omgodse.notally.security.PinSecurity
import com.omgodse.notally.security.SecureMedia
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.omgodse.notally.R
import com.omgodse.notally.databinding.RecyclerBaseNoteBinding
import com.omgodse.notally.miscellaneous.Operations
import com.omgodse.notally.miscellaneous.applySpans
import com.omgodse.notally.preferences.DateFormat
import com.omgodse.notally.preferences.TextSize
import com.omgodse.notally.recyclerview.ItemListener
import com.omgodse.notally.room.BaseNote
import com.omgodse.notally.room.Color
import com.omgodse.notally.room.Frequency
import com.omgodse.notally.room.Image
import com.omgodse.notally.room.ListItem
import com.omgodse.notally.room.Reminder
import com.omgodse.notally.room.SpanRepresentation
import com.omgodse.notally.room.Type
import org.ocpsoft.prettytime.PrettyTime
import java.io.File
import java.util.Date

class BaseNoteVH(
    private val binding: RecyclerBaseNoteBinding,
    private val dateFormat: String,
    private val textSize: String,
    private val maxItems: Int,
    maxLines: Int,
    maxTitle: Int,
    listener: ItemListener,
    private val prettyTime: PrettyTime,
    private val fullFormat: java.text.DateFormat,
    private val shortFormat: java.text.DateFormat
) : RecyclerView.ViewHolder(binding.root) {

    val bodySize = TextSize.getDisplayBodySize(textSize)
    private var showingLockedNote = false

    init {
        binding.Title.setTextSize(TypedValue.COMPLEX_UNIT_SP, TextSize.getDisplayTitleSize(textSize))
        binding.Date.setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySize)
        binding.Note.setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySize)
        binding.Reminder.setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySize)

        binding.LinearLayout.children.forEach { view ->
            view as TextView
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySize)
        }

        binding.Title.maxLines = maxTitle
        binding.Note.maxLines = maxLines

        binding.root.setOnClickListener {
            listener.onClick(adapterPosition)
        }

        binding.root.setOnLongClickListener {
            // La selección múltiple podría exportar una nota protegida sin PIN.
            if (showingLockedNote) listener.onClick(adapterPosition)
            else listener.onLongClick(adapterPosition)
            return@setOnLongClickListener true
        }
    }

    fun applyGroupShape(isGrid: Boolean, hasPrevious: Boolean, hasNext: Boolean) {
        val scale = binding.root.resources.displayMetrics.density
        val round = 18f * scale
        val joined = 4f * scale
        val top = if (isGrid || !hasPrevious) round else joined
        val bottom = if (isGrid || !hasNext) round else joined
        binding.root.shapeAppearanceModel = binding.root.shapeAppearanceModel.toBuilder()
            .setTopLeftCornerSize(top)
            .setTopRightCornerSize(top)
            .setBottomLeftCornerSize(bottom)
            .setBottomRightCornerSize(bottom)
            .build()
        val params = binding.root.layoutParams as? ViewGroup.MarginLayoutParams
        if (params != null) {
            params.bottomMargin = ((if (isGrid || !hasNext) 14f else 2f) * scale).toInt()
            binding.root.layoutParams = params
        }
    }

    fun updateCheck(checked: Boolean) {
        binding.root.isChecked = checked
    }

    fun bind(baseNote: BaseNote, mediaRoot: File?, checked: Boolean) {
        updateCheck(checked)
        val locked = PinSecurity.noteLocked(binding.root.context, baseNote.id)
        showingLockedNote = locked
        binding.LockIndicator.isVisible = locked
        binding.Space.isVisible = !locked
        binding.LabelGroup.isVisible = !locked
        if (locked) {
            binding.ImageView.visibility = View.GONE
            binding.Message.visibility = View.GONE
            binding.Title.text = binding.root.context.getString(R.string.locked_note)
            binding.Title.visibility = View.VISIBLE
            binding.Date.visibility = View.GONE
            binding.Note.visibility = View.GONE
            binding.LinearLayout.visibility = View.GONE
            binding.Reminder.visibility = View.GONE
            setColor(Color.DEFAULT)
            return
        }

        when (baseNote.type) {
            Type.NOTE -> bindNote(baseNote.body, baseNote.spans)
            Type.LIST -> bindList(baseNote.items)
        }

        setDate(baseNote.timestamp)
        setColor(baseNote.color)
        setImages(baseNote.images, mediaRoot)
        setReminder(baseNote.reminder)

        // Si no hay título, usa la primera línea del texto como título visual.
        val firstLine = if (baseNote.type == Type.NOTE) baseNote.body.lineSequence().firstOrNull().orEmpty() else ""
        val displayTitle = baseNote.title.ifBlank { if (baseNote.type == Type.LIST) binding.root.context.getString(R.string.task_list) else firstLine }
        binding.Title.text = displayTitle
        binding.Title.isVisible = displayTitle.isNotEmpty()
        if (baseNote.type == Type.NOTE && baseNote.title.isBlank() && firstLine.isNotBlank()) {
            val remainder = baseNote.body.removePrefix(firstLine).trimStart('\n')
            binding.Note.text = remainder
            binding.Note.isVisible = remainder.isNotEmpty()
        }

        Operations.bindLabels(binding.LabelGroup, baseNote.labels, bodySize)

        if (isEmpty(baseNote)) {
            binding.Title.setText(getEmptyMessage(baseNote))
            binding.Title.visibility = View.VISIBLE
        }
    }

    private fun bindNote(body: String, spans: List<SpanRepresentation>) {
        binding.LinearLayout.visibility = View.GONE

        binding.Note.text = body.applySpans(spans)
        binding.Note.isVisible = body.isNotEmpty()
    }

    private fun bindList(items: List<ListItem>) {
        val remaining = items.count { !it.checked }
        binding.Note.text = binding.root.context.resources.getQuantityString(R.plurals.task_items_summary, remaining, remaining)
        binding.Note.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE

        if (items.isEmpty()) {
            binding.LinearLayout.visibility = View.GONE
        } else {
            binding.LinearLayout.visibility = View.VISIBLE

            val filteredList = items.take(minOf(maxItems, 4))
            binding.LinearLayout.children.forEachIndexed { index, view ->
                if (view.id != R.id.ItemsRemaining) {
                    if (index < filteredList.size) {
                        val item = filteredList[index]
                        view as TextView
                        view.text = item.body
                        handleChecked(view, item.checked)
                        view.visibility = View.VISIBLE
                    } else view.visibility = View.GONE
                }
            }

            if (items.size > maxItems) {
                binding.ItemsRemaining.visibility = View.VISIBLE
                binding.ItemsRemaining.text = (items.size - maxItems).toString()
            } else binding.ItemsRemaining.visibility = View.GONE
        }
    }


    private fun setDate(timestamp: Long) {
        if (dateFormat != DateFormat.none) {
            binding.Date.visibility = View.VISIBLE
            val date = Date(timestamp)
            when (dateFormat) {
                DateFormat.relative -> binding.Date.text = prettyTime.format(date)
                DateFormat.absolute -> binding.Date.text = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(date)
            }
        } else binding.Date.visibility = View.GONE
    }

    private fun setColor(color: Color) {
        val context = binding.root.context

        val amoled = Preferences.getInstance(context.applicationContext as Application).isAmoledTheme()
        val glass = Preferences.getInstance(context.applicationContext as Application).isGlassTheme()
        if (glass && color == Color.DEFAULT) {
            binding.root.strokeWidth = (context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            binding.root.strokeColor = 0x55FFFFFF
            binding.root.setCardBackgroundColor(0x66273447)
        } else if (amoled && color == Color.DEFAULT) {
            binding.root.strokeColor = 0
            binding.root.setCardBackgroundColor(ContextCompat.getColor(context, R.color.NotasSurface))
        } else if (color == Color.DEFAULT) {
            binding.root.strokeColor = 0
            val attribute = android.util.TypedValue()
            context.theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, attribute, true)
            val surfaceColor = if (attribute.resourceId != 0) ContextCompat.getColor(context, attribute.resourceId) else attribute.data
            binding.root.setCardBackgroundColor(surfaceColor)
        } else {
            binding.root.strokeColor = 0
            binding.root.setCardBackgroundColor(Operations.extractColor(color, context))
        }
    }

    private fun setImages(images: List<Image>, mediaRoot: File?) {
        if (images.isNotEmpty()) {
            binding.ImageView.visibility = View.VISIBLE
            binding.Message.visibility = View.GONE

            val image = images[0]
            val file = if (mediaRoot != null) try {
                SecureMedia.readableFile(
                    binding.root.context.applicationContext as Application,
                    File(mediaRoot, image.name))
            } catch (_: Exception) { null } else null

            Glide.with(binding.ImageView)
                .load(file)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .listener(object : RequestListener<Drawable> {

                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>?,
                        isFirstResource: Boolean
                    ): Boolean {
                        binding.Message.visibility = View.VISIBLE
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable?,
                        model: Any?,
                        target: Target<Drawable>?,
                        dataSource: DataSource?,
                        isFirstResource: Boolean
                    ): Boolean {
                        return false
                    }
                })
                .into(binding.ImageView)
        } else {
            binding.ImageView.visibility = View.GONE
            binding.Message.visibility = View.GONE
            Glide.with(binding.ImageView).clear(binding.ImageView)
        }
    }

    private fun setReminder(reminder: Reminder?) {
        if (reminder != null) {
            val date = shortFormat.format(reminder.timestamp)
            val context = binding.root.context
            binding.Reminder.text = when (reminder.frequency) {
                Frequency.ONCE -> date
                Frequency.DAILY -> context.getString(R.string.repeats_daily, date)
                Frequency.MONTHLY -> context.getString(R.string.repeats_monthly, date)
            }
            binding.Reminder.visibility = View.VISIBLE
        } else binding.Reminder.visibility = View.GONE
    }


    private fun isEmpty(baseNote: BaseNote): Boolean {
        return when (baseNote.type) {
            Type.NOTE -> baseNote.title.isBlank() && baseNote.body.isBlank() && baseNote.images.isEmpty()
            Type.LIST -> baseNote.title.isBlank() && baseNote.items.isEmpty() && baseNote.images.isEmpty()
        }
    }

    private fun getEmptyMessage(baseNote: BaseNote): Int {
        return when (baseNote.type) {
            Type.NOTE -> R.string.empty_note
            Type.LIST -> R.string.empty_list
        }
    }

    private fun handleChecked(textView: TextView, checked: Boolean) {
        if (checked) {
            textView.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.checkbox_16, 0, 0, 0)
        } else textView.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.checkbox_outline_16, 0, 0, 0)
    }
}