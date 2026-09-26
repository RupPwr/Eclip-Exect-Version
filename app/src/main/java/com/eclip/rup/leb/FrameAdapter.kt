package com.eclip.rup.leb

import android.graphics.Bitmap
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.eclip.rup.leb.utils.BitmapPool
import com.eclip.rup.leb.utils.TimelineRenderMode

class FrameAdapter(
    private var frameBitmaps: List<Bitmap>,
    var itemWidth: Int,
    var placeholderColor: Int = 0xFF1E2028.toInt()
) : RecyclerView.Adapter<FrameAdapter.FrameViewHolder>() {

    var renderMode: TimelineRenderMode = TimelineRenderMode.HIGH_FIDELITY
        private set

    class FrameViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.frameImageView)
        var currentBitmap: Bitmap? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FrameViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_frame, parent, false)
        return FrameViewHolder(view)
    }

    override fun onBindViewHolder(holder: FrameViewHolder, position: Int) {
        holder.itemView.layoutParams = (holder.itemView.layoutParams ?: ViewGroup.LayoutParams(
            itemWidth,
            ViewGroup.LayoutParams.MATCH_PARENT
        )).apply {
            width = itemWidth
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }

        // Release previously bound bitmap reference if this holder is being rebound
        val oldBmp = holder.currentBitmap
        if (oldBmp != null) {
            BitmapPool.releaseFromUse(oldBmp)
            holder.currentBitmap = null
        }

        // Render full-quality thumbnail bitmap whenever available
        if (position < frameBitmaps.size) {
            val bmp = frameBitmaps[position]
            if (!bmp.isRecycled) {
                BitmapPool.markInUse(bmp)
                holder.currentBitmap = bmp
                holder.imageView.setImageBitmap(bmp)
                holder.imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                holder.imageView.setBackgroundColor(Color.TRANSPARENT)
            } else {
                holder.imageView.setImageDrawable(null)
                holder.imageView.setBackgroundColor(placeholderColor)
            }
        } else if (frameBitmaps.isNotEmpty()) {
            val fallbackBmp = frameBitmaps[0]
            if (!fallbackBmp.isRecycled) {
                BitmapPool.markInUse(fallbackBmp)
                holder.currentBitmap = fallbackBmp
                holder.imageView.setImageBitmap(fallbackBmp)
                holder.imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                holder.imageView.setBackgroundColor(Color.TRANSPARENT)
            } else {
                holder.imageView.setImageDrawable(null)
                holder.imageView.setBackgroundColor(placeholderColor)
            }
        } else {
            holder.imageView.setImageDrawable(null)
            holder.imageView.setBackgroundColor(placeholderColor)
        }
    }

    override fun onViewRecycled(holder: FrameViewHolder) {
        super.onViewRecycled(holder)
        val bmp = holder.currentBitmap
        if (bmp != null) {
            BitmapPool.releaseFromUse(bmp)
            holder.currentBitmap = null
        }
        holder.imageView.setImageDrawable(null)
    }

    override fun getItemCount(): Int = 15

    fun setRenderMode(newMode: TimelineRenderMode) {
        if (this.renderMode != newMode) {
            this.renderMode = newMode
        }
    }

    fun updateFrames(newFrames: List<Bitmap>) {
        this.frameBitmaps = newFrames
        try {
            if (hasObservers()) {
                notifyDataSetChanged()
            }
        } catch (e: Throwable) {
            // Ignore when detached or during layout computation
        }
    }

    fun addFrame(bitmap: Bitmap) {
        val mut = this.frameBitmaps.toMutableList()
        val isFirst = mut.isEmpty()
        mut.add(bitmap)
        this.frameBitmaps = mut
        try {
            if (hasObservers()) {
                if (isFirst) {
                    notifyDataSetChanged()
                } else {
                    notifyItemChanged(mut.size - 1)
                }
            }
        } catch (e: Throwable) {
            // Ignore when detached or during layout computation
        }
    }

    fun updateItemWidth(newItemWidth: Int) {
        if (this.itemWidth != newItemWidth) {
            this.itemWidth = newItemWidth
            try {
                if (hasObservers()) {
                    notifyDataSetChanged()
                }
            } catch (e: Throwable) {
                // Ignore when detached or during layout computation
            }
        }
    }
}

