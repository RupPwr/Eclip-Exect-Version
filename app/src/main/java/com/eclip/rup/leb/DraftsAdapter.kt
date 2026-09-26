package com.eclip.rup.leb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.eclip.rup.leb.utils.DraftItem
import com.eclip.rup.leb.utils.setBounceClickListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DraftsAdapter(
    private var drafts: List<DraftItem>,
    private val onDraftClick: (DraftItem) -> Unit,
    private val onDraftDelete: (DraftItem) -> Unit
) : RecyclerView.Adapter<DraftsAdapter.DraftViewHolder>() {

    companion object {
        private val thumbnailMemoryCache = object : LruCache<String, Bitmap>(
            (Runtime.getRuntime().maxMemory() / 16).toInt().coerceIn(4 * 1024 * 1024, 32 * 1024 * 1024)
        ) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount
            }
        }

        fun decodeSampledBitmapFromFile(file: File, reqWidth: Int = 240, reqHeight: Int = 240): Bitmap? {
            return try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.absolutePath, options)

                var inSampleSize = 1
                val height = options.outHeight
                val width = options.outWidth
                if (height > reqHeight || width > reqWidth) {
                    val halfHeight = height / 2
                    val halfWidth = width / 2
                    while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                        inSampleSize *= 2
                    }
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            } catch (e: Exception) {
                null
            }
        }
    }

    private val adapterScope = CoroutineScope(Dispatchers.Main)

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
        var thumbnailLoadJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DraftViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_draft_project, parent, false)
        return DraftViewHolder(view)
    }

    override fun onBindViewHolder(holder: DraftViewHolder, position: Int) {
        val draft = drafts[position]
        holder.tvTitle.text = draft.title
        holder.tvDuration.text = draft.getFormattedDuration()

        holder.thumbnailLoadJob?.cancel()
        val thumbFile = draft.thumbnailFile
        val thumbKey = draft.id

        if (thumbFile != null && thumbFile.exists()) {
            val cached = thumbnailMemoryCache.get(thumbKey)
            if (cached != null && !cached.isRecycled) {
                holder.ivThumbnail.setImageBitmap(cached)
                holder.ivThumbnail.visibility = View.VISIBLE
                holder.ivPlaceholder.visibility = View.GONE
            } else {
                holder.ivThumbnail.setImageDrawable(null)
                holder.ivThumbnail.visibility = View.GONE
                holder.ivPlaceholder.visibility = View.VISIBLE

                holder.ivThumbnail.tag = thumbKey
                holder.thumbnailLoadJob = adapterScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        decodeSampledBitmapFromFile(thumbFile)
                    }
                    if (bitmap != null) {
                        thumbnailMemoryCache.put(thumbKey, bitmap)
                        if (holder.ivThumbnail.tag == thumbKey) {
                            holder.ivThumbnail.setImageBitmap(bitmap)
                            holder.ivThumbnail.visibility = View.VISIBLE
                            holder.ivPlaceholder.visibility = View.GONE
                        }
                    }
                }
            }
        } else {
            holder.ivThumbnail.setImageDrawable(null)
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

    override fun onViewRecycled(holder: DraftViewHolder) {
        super.onViewRecycled(holder)
        holder.thumbnailLoadJob?.cancel()
        holder.thumbnailLoadJob = null
        holder.ivThumbnail.setImageDrawable(null)
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
