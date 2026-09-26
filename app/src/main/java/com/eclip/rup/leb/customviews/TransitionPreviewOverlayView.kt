package com.eclip.rup.leb.customviews

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.eclip.rup.leb.utils.HardwareTimelineRenderer

class TransitionPreviewOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var transitionType: String = "none"
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private var snapshotBitmap: Bitmap? = null
    private val renderer = HardwareTimelineRenderer()
    private val dstRect = RectF()

    fun updateTransition(type: String, prog: Float, bitmap: Bitmap?) {
        this.transitionType = type.lowercase()
        this.progress = prog.coerceIn(0f, 1f)
        if (this.snapshotBitmap != bitmap) {
            this.snapshotBitmap = bitmap
        }
        invalidate()
    }

    fun clearSnapshot() {
        this.snapshotBitmap = null
        this.transitionType = "none"
        this.progress = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = snapshotBitmap
        if (bmp == null || bmp.isRecycled || transitionType == "none" || progress <= 0f || progress >= 1f) {
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        dstRect.set(0f, 0f, w, h)
        renderer.drawTransition(canvas, transitionType, progress, bmp, dstRect)
    }
}

