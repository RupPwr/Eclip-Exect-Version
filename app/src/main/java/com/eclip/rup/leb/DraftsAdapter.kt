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

    class DraftViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.cardDraftRoot)
        val ivThumbnail: ImageView = view.findViewById(R.id.ivDraftThumbnail)
        val ivPlaceholder: ImageView = view.findViewById(R.id.ivDraftPlaceholder)
        val tvTitle: TextView = view.findViewById(R.id.tvDraftTitle)
        val tvDuration: TextView = view.findViewById(R.id.tvDraftDuration)
        val btnOptions: ImageView = view.findViewById(R.id.btnDraftOptions)
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

        holder.root.setBounceClickListener {
            onDraftClick(draft)
        }

        holder.btnOptions.setOnClickListener { v ->
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

    override fun getItemCount(): Int = drafts.size

    fun updateData(newDrafts: List<DraftItem>) {
        drafts = newDrafts
        notifyDataSetChanged()
    }
}
