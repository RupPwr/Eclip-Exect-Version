package com.eclip.rup.leb.customviews

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.HorizontalScrollView
import com.eclip.rup.leb.utils.HardwareTimelineRenderer

/**
 * TimeRulerView renders time scale markers and timestamps using a zero-allocation,
 * viewport-culled procedural grid.
 */
class TimeRulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var videoDurationMs: Long = 0L
    var customMsPerPixel: Float? = null
    private val renderer = HardwareTimelineRenderer()
    private val clipBounds = Rect()
    private var parentScrollView: HorizontalScrollView? = null
    private var cachedDensity: Float = 0f

    init {
        cachedDensity = resources.displayMetrics.density
    }

    fun setVideoDuration(durationMs: Long) {
        if (this.videoDurationMs != durationMs) {
            this.videoDurationMs = durationMs
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        cachedDensity = resources.displayMetrics.density
        findParentScrollView()
    }

    private fun findParentScrollView(): HorizontalScrollView? {
        if (parentScrollView != null) return parentScrollView
        var p = parent
        while (p != null) {
            if (p is HorizontalScrollView) {
                parentScrollView = p
                return p
            }
            p = (p as? View)?.parent
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (videoDurationMs <= 0 || width <= 0) return

        val msPerPixel = customMsPerPixel ?: (videoDurationMs.toFloat() / width)
        if (msPerPixel <= 0f) return

        val density = if (cachedDensity > 0f) cachedDensity else resources.displayMetrics.density
        
        // Zero-Layout Viewport Culling: calculate visible range from parent HorizontalScrollView
        val hScroll = findParentScrollView()
        val viewportLeft: Float
        val viewportRight: Float

        if (hScroll != null && hScroll.width > 0) {
            val scrollX = hScroll.scrollX.toFloat()
            val paddingLeft = hScroll.paddingLeft.toFloat()
            val viewportW = hScroll.width.toFloat()
            val bufferPx = 100f * density
            viewportLeft = (scrollX - paddingLeft - bufferPx).coerceAtLeast(0f)
            viewportRight = (scrollX - paddingLeft + viewportW + bufferPx).coerceAtMost(width.toFloat())
        } else {
            canvas.getClipBounds(clipBounds)
            viewportLeft = clipBounds.left.toFloat().coerceAtLeast(0f)
            viewportRight = clipBounds.right.toFloat().coerceAtMost(width.toFloat())
        }

        renderer.drawProceduralGridCulled(
            canvas,
            videoDurationMs,
            msPerPixel,
            width.toFloat(),
            height.toFloat(),
            viewportLeft,
            viewportRight,
            density
        )
    }
}
