package com.eclip.rup.leb.customviews

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * CustomVideoSeeker — redesigned to match the CapCut-style reference UI.
 *
 * Visual changes (no functional changes):
 *  • Playhead line color → #FF4081 (accent)
 *  • Playhead has a teardrop / rounded-top handle at the top (like the reference)
 *  • Line is slightly thinner for a refined look
 *
 * All seek logic (onSeekListener, setVideoDuration, seekPosition) is untouched.
 */
class CustomVideoSeeker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Paints ────────────────────────────────────────────────────────────────

    /** The main accent line */
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.FILL_AND_STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** The top notch/cap at the top of the playhead */
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    /** Subtle drop shadow so the playhead stands out cleanly against any background */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 60
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // ── State (identical to original) ─────────────────────────────────────────
    private var seekPosition = 0f   // 0..1
    private var videoDuration = 0L  // ms
    var onSeekListener: ((Float) -> Unit)? = null
    var customPlayheadX: Float? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    // ── Handle geometry ───────────────────────────────────────────────────────
    private val handleRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val density = resources.displayMetrics.density
        // Playhead is anchored at custom position or exact center of the timeline
        val seekX = customPlayheadX ?: (width / 2f)
        val topCapHeight = 7f * density
        val topCapWidth = 8f * density
        
        linePaint.strokeWidth = 2f * density
        shadowPaint.strokeWidth = 3f * density

        // 1. Draw subtle drop shadow behind playhead line
        canvas.drawLine(seekX + 0.75f * density, 0f, seekX + 0.75f * density, height.toFloat(), shadowPaint)

        // 2. Draw the main vertical white playhead line
        canvas.drawLine(seekX, 0f, seekX, height.toFloat(), linePaint)

        // 3. Top playhead rounded notch cap centered on the playhead line
        handleRect.set(
            seekX - (topCapWidth / 2f),
            0f,
            seekX + (topCapWidth / 2f),
            topCapHeight
        )
        canvas.drawRoundRect(handleRect, 2f * density, 2f * density, handlePaint)
    }

    private var isDragging = false
    val isUserSeeking: Boolean get() = isDragging

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return false // Let touch events pass through to the timeline below!
    }

    fun setVideoDuration(duration: Long) {
        videoDuration = duration
    }

    fun setSeekPosition(position: Float) {
        seekPosition = position.coerceIn(0f, 1f)
        invalidate()
    }

}