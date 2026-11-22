package com.omgodse.notally.recyclerview.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.omgodse.notally.databinding.RecyclerBaseNoteBinding
import com.omgodse.notally.databinding.RecyclerHeaderBinding
import com.omgodse.notally.recyclerview.ItemListener
import com.omgodse.notally.recyclerview.viewholder.BaseNoteVH
import com.omgodse.notally.recyclerview.viewholder.HeaderVH
import com.omgodse.notally.room.BaseNote
import com.omgodse.notally.room.Header
import com.omgodse.notally.room.Item
import com.omgodse.notally.preferences.View as ViewPref
import org.ocpsoft.prettytime.PrettyTime
import java.io.File
import java.text.DateFormat

class BaseNoteAdapter(
    private val selectedIds: Set<Long>,
    private val dateFormat: String,
    private val textSize: String,
    private val maxItems: Int,
    private val maxLines: Int,
    private val maxTitle: Int,
    private val fullFormat: DateFormat,
    private val shortFormat: DateFormat,
    private val mediaRoot: File?,
    private val listener: ItemListener,
    private val viewMode: String = ViewPref.list
) : ListAdapter<Item, RecyclerView.ViewHolder>(DiffCallback) {

    private val prettyTime = PrettyTime()

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is Header -> 0
            is BaseNote -> 1
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is Header -> (holder as HeaderVH).bind(item)
            is BaseNote -> {
                val prevNote = position > 0 && getItem(position - 1) is BaseNote
                val nextNote = position < itemCount - 1 && getItem(position + 1) is BaseNote
                (holder as BaseNoteVH).applyGroupShape(viewMode == ViewPref.grid, prevNote, nextNote)
                try {
                    holder.bind(item, mediaRoot, selectedIds.contains(item.id))
                } catch (error: Exception) {
                    // Un enlace/formato inválido de una nota no debe impedir
                    // abrir Inicio ni el resto de las notas.
                    android.util.Log.e("NotasLinkRecovery", "Could not render note preview ID ${item.id}", error)
                    holder.bindUnreadable(item)
                }
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
        } else handleCheck(holder, position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> {
                val binding = RecyclerHeaderBinding.inflate(inflater, parent, false)
                if (viewMode == ViewPref.grid) {
                    // Las cabeceras de Fijadas/Otras abarcan ambas columnas.
                    binding.root.layoutParams = StaggeredGridLayoutManager.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { isFullSpan = true }
                }
                HeaderVH(binding)
            }
            else -> {
                val binding = RecyclerBaseNoteBinding.inflate(inflater, parent, false)
                BaseNoteVH(binding, dateFormat, textSize, maxItems, maxLines, maxTitle, listener, prettyTime, fullFormat, shortFormat)
            }
        }
    }


    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is HeaderVH) {
            (holder.itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams)
                ?.isFullSpan = true
        }
    }

    private fun handleCheck(holder: RecyclerView.ViewHolder, position: Int) {
        val baseNote = getItem(position) as BaseNote
        (holder as BaseNoteVH).updateCheck(selectedIds.contains(baseNote.id))
    }


    private object DiffCallback : DiffUtil.ItemCallback<Item>() {

        override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean {
            return when (oldItem) {
                is BaseNote -> if (newItem is BaseNote) {
                    oldItem.id == newItem.id
                } else false
                is Header -> if (newItem is Header) {
                    oldItem.label == newItem.label
                } else false
            }
        }

        override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean {
            return when (oldItem) {
                is BaseNote -> if (newItem is BaseNote) {
                    oldItem == newItem
                } else false
                is Header -> if (newItem is Header) {
                    oldItem.label == newItem.label
                } else false
            }
        }
    }
}