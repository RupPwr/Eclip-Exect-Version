package com.eclip.rup.leb

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.eclip.rup.leb.utils.DraftItem
import com.eclip.rup.leb.utils.setBounceClickListener

class DraftsAdapter(
    private var drafts: List<DraftItem>,
    private val onDraftClick: (DraftItem) -> Unit,
    private val onDraftDelete: (DraftItem) -> Unit
) : RecyclerView.Adapter<DraftsAdapter.DraftViewHolder>() {

    var isSelectionMode: Boolean = false
        private set

    val selectedDraftIds = mutableSetOf<String>()
    var onSelectionChangedListener: ((selectedCount: Int, totalCount: Int) -> Unit)? = null

    class DraftViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.cardDraftRoot)
        val ivThumbnail: ImageView = view.findViewById(R.id.ivDraftThumbnail)
        val ivPlaceholder: ImageView = view.findViewById(R.id.ivDraftPlaceholder)
        val vSelectedOverlay: View = view.findViewById(R.id.vDraftSelectedOverlay)
        val tvTitle: TextView = view.findViewById(R.id.tvDraftTitle)
        val tvDuration: TextView = view.findViewById(R.id.tvDraftDuration)
        val layoutActionContainer: View = view.findViewById(R.id.layoutDraftActionContainer)
        val btnOptions: ImageView = view.findViewById(R.id.btnDraftOptions)
        val ivCheck: ImageView = view.findViewById(R.id.ivDraftCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DraftViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_draft_project, parent, false)
        return DraftViewHolder(view)
    }

    override fun onBindViewHolder(holder: DraftViewHolder, position: Int) {
        val draft = drafts[position]
        holder.tvTitle.text = draft.title
        holder.tvDuration.text = draft.getFormattedDuration()

        if (draft.thumbnailFile != null && draft.thumbnailFile.exists()) {
            try {
                val bitmap = BitmapFactory.decodeFile(draft.thumbnailFile.absolutePath)
                if (bitmap != null) {
                    holder.ivThumbnail.setImageBitmap(bitmap)
                    holder.ivThumbnail.visibility = View.VISIBLE
                    holder.ivPlaceholder.visibility = View.GONE
                } else {
                    holder.ivThumbnail.visibility = View.GONE
                    holder.ivPlaceholder.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                holder.ivThumbnail.visibility = View.GONE
                holder.ivPlaceholder.visibility = View.VISIBLE
            }
        } else {
            holder.ivThumbnail.visibility = View.GONE
            holder.ivPlaceholder.visibility = View.VISIBLE
        }

        if (isSelectionMode) {
            holder.btnOptions.visibility = View.GONE
            holder.btnOptions.setOnClickListener(null)
            holder.ivCheck.visibility = View.VISIBLE

            val isSelected = selectedDraftIds.contains(draft.id)
            holder.vSelectedOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE

            if (isSelected) {
                holder.ivCheck.setBackgroundResource(R.drawable.bg_draft_check_selected)
                holder.ivCheck.setImageResource(R.drawable.ic_check_24)
            } else {
                holder.ivCheck.setBackgroundResource(R.drawable.bg_draft_check_unselected)
                holder.ivCheck.setImageDrawable(null)
            }

            val toggleAction = View.OnClickListener {
                toggleSelection(draft)
            }
            holder.root.setOnClickListener(toggleAction)
            holder.ivCheck.setOnClickListener(toggleAction)
            holder.layoutActionContainer.setOnClickListener(toggleAction)
        } else {
            holder.layoutActionContainer.setOnClickListener(null)
            holder.ivCheck.setOnClickListener(null)
            holder.btnOptions.visibility = View.VISIBLE
            holder.ivCheck.visibility = View.GONE
            holder.vSelectedOverlay.visibility = View.GONE

            holder.root.setBounceClickListener {
                if (!isSelectionMode) {
                    onDraftClick(draft)
                }
            }

            holder.btnOptions.setOnClickListener { v ->
                if (isSelectionMode) return@setOnClickListener
                val popup = PopupMenu(v.context, v)
                popup.menu.add(0, 1, 0, R.string.str_open)
                popup.menu.add(0, 2, 1, R.string.str_delete_draft)
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> {
                            onDraftClick(draft)
                            true
                        }
                        2 -> {
                            onDraftDelete(draft)
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }
        }
    }

    override fun getItemCount(): Int = drafts.size

    fun toggleSelectionMode(enabled: Boolean) {
        isSelectionMode = enabled
        if (!enabled) {
            selectedDraftIds.clear()
        }
        notifyDataSetChanged()
        onSelectionChangedListener?.invoke(selectedDraftIds.size, drafts.size)
    }

    fun toggleSelection(draft: DraftItem) {
        if (selectedDraftIds.contains(draft.id)) {
            selectedDraftIds.remove(draft.id)
        } else {
            selectedDraftIds.add(draft.id)
        }
        notifyDataSetChanged()
        onSelectionChangedListener?.invoke(selectedDraftIds.size, drafts.size)
    }

    fun selectAll() {
        selectedDraftIds.clear()
        selectedDraftIds.addAll(drafts.map { it.id })
        notifyDataSetChanged()
        onSelectionChangedListener?.invoke(selectedDraftIds.size, drafts.size)
    }

    fun deselectAll() {
        selectedDraftIds.clear()
        notifyDataSetChanged()
        onSelectionChangedListener?.invoke(selectedDraftIds.size, drafts.size)
    }

    fun isAllSelected(): Boolean = drafts.isNotEmpty() && selectedDraftIds.size == drafts.size

    fun getSelectedDrafts(): List<DraftItem> = drafts.filter { selectedDraftIds.contains(it.id) }

    fun updateData(newDrafts: List<DraftItem>) {
        drafts = newDrafts
        // Remove any selected IDs that no longer exist
        val currentIds = newDrafts.map { it.id }.toSet()
        selectedDraftIds.retainAll(currentIds)
        notifyDataSetChanged()
        onSelectionChangedListener?.invoke(selectedDraftIds.size, drafts.size)
    }
}
