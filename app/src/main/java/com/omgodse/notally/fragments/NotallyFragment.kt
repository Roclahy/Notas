package com.omgodse.notally.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.omgodse.notally.activities.MakeList
import com.omgodse.notally.activities.TakeNote
import com.omgodse.notally.databinding.FragmentNotesBinding
import com.omgodse.notally.miscellaneous.Constants
import com.omgodse.notally.security.PinSecurity
import com.omgodse.notally.recyclerview.ItemListener
import com.omgodse.notally.recyclerview.adapter.BaseNoteAdapter
import com.omgodse.notally.room.BaseNote
import com.omgodse.notally.room.Item
import com.omgodse.notally.room.Type
import com.omgodse.notally.viewmodels.BaseNoteModel
import java.text.DateFormat
import com.omgodse.notally.preferences.View as ViewPref

abstract class NotallyFragment : Fragment(), ItemListener {

    private var adapter: BaseNoteAdapter? = null
    internal var binding: FragmentNotesBinding? = null

    internal val model: BaseNoteModel by activityViewModels()

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
        adapter = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding?.ImageView?.setImageResource(getBackground())

        // Instalar primero la cuadrícula LTR, ANTES de su primera lista.
        // Evita la pasada inicial con un span a la derecha.
        setupRecyclerView()
        setupAdapter()
        setupObserver()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        setHasOptionsMenu(true)
        binding = FragmentNotesBinding.inflate(inflater)
        return binding?.root
    }


    // See [RecyclerView.ViewHolder.getAdapterPosition]
    override fun onClick(position: Int) {
        if (position != -1) {
            adapter?.currentList?.get(position)?.let { item ->
                if (item is BaseNote) {
                    if (model.actionMode.isEnabled()) {
                        handleNoteSelection(item.id, position, item)
                    } else {
                        when (item.type) {
                            Type.NOTE -> goToActivity(TakeNote::class.java, item)
                            Type.LIST -> goToActivity(MakeList::class.java, item)
                        }
                    }
                }
            }
        }
    }

    override fun onLongClick(position: Int) {
        if (position != -1) {
            adapter?.currentList?.get(position)?.let { item ->
                if (item is BaseNote) {
                    handleNoteSelection(item.id, position, item)
                }
            }
        }
    }

    private fun handleNoteSelection(id: Long, position: Int, baseNote: BaseNote) {
        if (model.actionMode.selectedNotes.contains(id)) {
            model.actionMode.remove(id)
        } else model.actionMode.add(id, baseNote)
        adapter?.notifyItemChanged(position, 0)
    }


    private fun setupAdapter() {
        val textSize = model.preferences.textSize.value
        val maxItems = model.preferences.maxItems
        val maxLines = model.preferences.maxLines
        val maxTitle = model.preferences.maxTitle
        val dateFormat = model.preferences.dateFormat.value
        val fullFormat = DateFormat.getDateInstance(DateFormat.FULL)
        val shortFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

        adapter = BaseNoteAdapter(model.actionMode.selectedIds, dateFormat, textSize, maxItems, maxLines, maxTitle, fullFormat, shortFormat, model.mediaRoot, this, model.preferences.view.value)
        binding?.RecyclerView?.adapter = adapter
        binding?.RecyclerView?.setHasFixedSize(true)
        // Sin animaciones predictivas de reordenamiento: evita que la primera
        // tarjeta salte desde la columna derecha a la izquierda.
        binding?.RecyclerView?.itemAnimator = null
    }

    private fun setupObserver() {
        // Los PIN se guardan en SharedPreferences, no en Room. DiffUtil no
        // detecta un cambio de candado cuando BaseNote sigue siendo igual.
        // Al regresar desde el editor, LiveData entrega el último cambio
        // pendiente y fuerza un bind completo solo de la tarjeta afectada.
        PinSecurity.noteChanges.observe(viewLifecycleOwner) { changedId ->
            val notesAdapter = adapter ?: return@observe
            if (changedId == -1L) {
                notesAdapter.notifyDataSetChanged()
            } else {
                val position = notesAdapter.currentList.indexOfFirst {
                    it is BaseNote && it.id == changedId
                }
                if (position >= 0) notesAdapter.notifyItemChanged(position)
            }
        }

        var previousNotebookId: Long? = null
        var resetPosition = true
        model.selectedNotebookId.observe(viewLifecycleOwner) { id ->
            if (id != previousNotebookId) {
                previousNotebookId = id
                resetPosition = true
                binding?.RecyclerView?.stopScroll()
                binding?.RecyclerView?.scrollToPosition(0)
            }
        }
        getObservable().observe(viewLifecycleOwner) { list ->
            adapter?.submitList(list) {
                if (resetPosition) {
                    resetPosition = false
                    val recycler = binding?.RecyclerView
                    // Las carpetas no heredan asignaciones de columna previas.
                    (recycler?.layoutManager as? StaggeredGridLayoutManager)
                        ?.invalidateSpanAssignments()
                    recycler?.scrollToPosition(0)
                }
            }
            binding?.ImageView?.isVisible = list.isEmpty()
        }

        model.actionMode.closeListener.observe(viewLifecycleOwner) { event ->
            event.handle { ids ->
                adapter?.currentList?.forEachIndexed { index, item ->
                    if (item is BaseNote && ids.contains(item.id)) {
                        adapter?.notifyItemChanged(index, 0)
                    }
                }
            }
        }
    }

    private fun setupRecyclerView() {
        binding?.RecyclerView?.apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isSaveEnabled = false // Nunca restaurar ancla de columna de otra carpeta.
        }
        binding?.RecyclerView?.layoutManager = if (model.preferences.view.value == ViewPref.grid) {
            // La altura de cada tarjeta depende solo de su propio contenido
            // y de los limites de densidad: maxTitle, maxLines, maxItems.
            StaggeredGridLayoutManager(2, RecyclerView.VERTICAL).apply {
                reverseLayout = false
                // Evitar recolocaciones que podrian invertir las columnas.
                gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
            }
        } else LinearLayoutManager(requireContext())
    }


    private fun goToActivity(activity: Class<*>, baseNote: BaseNote) {
        val intent = Intent(requireContext(), activity)
        intent.putExtra(Constants.SelectedBaseNote, baseNote.id)
        startActivity(intent)
    }


    abstract fun getBackground(): Int

    abstract fun getObservable(): LiveData<List<Item>>
}