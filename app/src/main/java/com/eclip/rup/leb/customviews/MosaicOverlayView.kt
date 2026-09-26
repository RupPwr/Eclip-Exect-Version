package com.eclip.rup.leb.customviews

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.eclip.rup.leb.models.EditOperation
import kotlin.math.max
import kotlin.math.min

class MosaicOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var relativeX = 0.5f
    var relativeY = 0.5f
    var relativeWidth = 0.35f
    var relativeHeight = 0.35f
    var intensity = 15 // 5..50
    var shape = EditOperation.MaskShape.RECTANGLE

    var onMosaicChanged: ((Float, Float, Float, Float, Int, EditOperation.MaskShape) -> Unit)? = null

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A90E2")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(16f, 8f), 0f)
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A90E2")
        style = Paint.Style.FILL
    }

    private val handleInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val mosaicGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val mosaicOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activeHandle = Handle.NONE

    private enum class Handle {
        NONE, BODY, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    private val rectF = RectF()
    private val handleRadius = 20f

    var isEditingMode: Boolean = false
    var currentPositionMs: Long = 0L
        set(value) {
            if (field != value) {
                field = value
                if (!isEditingMode && mosaicOperations.isNotEmpty()) {
                    invalidate()
                }
            }
        }
    var hiddenOperationId: String? = null
        set(value) {
            field = value
            invalidate()
        }
    var mosaicOperations: List<EditOperation.MosaicOverlay> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    fun setMosaicConfig(
        relX: Float,
        relY: Float,
        relW: Float,
        relH: Float,
        intens: Int,
        shp: EditOperation.MaskShape
    ) {
        relativeX = relX
        relativeY = relY
        relativeWidth = relW
        relativeHeight = relH
        intensity = intens
        shape = shp
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        if (isEditingMode) {
            // Draw active editable mosaic overlay with handles & border
            drawMosaicPatch(canvas, w, h, relativeX, relativeY, relativeWidth, relativeHeight, intensity, shape, drawHandlesAndBorder = true)
        } else {
            // Draw active project mosaic operations based on current timestamp
            for (op in mosaicOperations) {
                if (op.id == hiddenOperationId) continue
                val start = op.startTimeMs ?: 0L
                val end = op.endTimeMs ?: (start + 4000L)
                if (currentPositionMs in start..end) {
                    drawMosaicPatch(canvas, w, h, op.relativeX, op.relativeY, op.relativeWidth, op.relativeHeight, op.intensity, op.shape, drawHandlesAndBorder = false)
                }
            }
        }
    }

    private fun drawMosaicPatch(
        canvas: Canvas,
        w: Float,
        h: Float,
        relX: Float,
        relY: Float,
        relW: Float,
        relH: Float,
        intens: Int,
        shp: EditOperation.MaskShape,
        drawHandlesAndBorder: Boolean
    ) {
        val centerX = relX * w
        val centerY = relY * h
        val halfW = (relW * w) / 2f
        val halfH = (relH * h) / 2f

        rectF.set(centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH)

        // Draw simulated pixelated mosaic grid inside bounds
        canvas.save()
        if (shp == EditOperation.MaskShape.ELLIPSE) {
            val path = Path().apply { addOval(rectF, Path.Direction.CW) }
            canvas.clipPath(path)
        } else {
            canvas.clipRect(rectF)
        }

        val blockSize = max(8f, (intens * 2.5f))
        var curY = rectF.top
        var seed = 42
        while (curY < rectF.bottom) {
            var curX = rectF.left
            while (curX < rectF.right) {
                seed = (seed * 1103515245 + 12345) and 0x7fffffff
                val grayVal = 60 + (seed % 140)
                val alphaVal = 180 + (seed % 60)
                mosaicGridPaint.color = Color.argb(alphaVal, grayVal, grayVal, grayVal)

                val rW = min(blockSize, rectF.right - curX)
                val rH = min(blockSize, rectF.bottom - curY)
                canvas.drawRect(curX, curY, curX + rW, curY + rH, mosaicGridPaint)
                canvas.drawRect(curX, curY, curX + rW, curY + rH, mosaicOutlinePaint)
                curX += blockSize
            }
            curY += blockSize
        }
        canvas.restore()

        if (drawHandlesAndBorder) {
            // Draw border
            if (shp == EditOperation.MaskShape.ELLIPSE) {
                canvas.drawOval(rectF, borderPaint)
            } else {
                canvas.drawRect(rectF, borderPaint)
            }

            // Draw resize handles
            drawHandle(canvas, rectF.left, rectF.top)
            drawHandle(canvas, rectF.right, rectF.top)
            drawHandle(canvas, rectF.left, rectF.bottom)
            drawHandle(canvas, rectF.right, rectF.bottom)
        }
    }

    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, handleRadius, handlePaint)
        canvas.drawCircle(cx, cy, handleRadius * 0.6f, handleInnerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditingMode) return false
        val x = event.x
        val y = event.y
        val w = width.toFloat()
        val h = height.toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                activeHandle = getHitHandle(x, y)
                return activeHandle != Handle.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                lastTouchX = x
                lastTouchY = y

                when (activeHandle) {
                    Handle.BODY -> {
                        relativeX = (relativeX + dx / w).coerceIn(0.05f, 0.95f)
                        relativeY = (relativeY + dy / h).coerceIn(0.05f, 0.95f)
                    }
                    Handle.TOP_LEFT -> {
                        relativeWidth = (relativeWidth - (dx / w) * 2f).coerceIn(0.1f, 0.9f)
                        relativeHeight = (relativeHeight - (dy / h) * 2f).coerceIn(0.1f, 0.9f)
                    }
                    Handle.TOP_RIGHT -> {
                        relativeWidth = (relativeWidth + (dx / w) * 2f).coerceIn(0.1f, 0.9f)
                        relativeHeight = (relativeHeight - (dy / h) * 2f).coerceIn(0.1f, 0.9f)
                    }
                    Handle.BOTTOM_LEFT -> {
                        relativeWidth = (relativeWidth - (dx / w) * 2f).coerceIn(0.1f, 0.9f)
                        relativeHeight = (relativeHeight + (dy / h) * 2f).coerceIn(0.1f, 0.9f)
                    }
                    Handle.BOTTOM_RIGHT -> {
                        relativeWidth = (relativeWidth + (dx / w) * 2f).coerceIn(0.1f, 0.9f)
                        relativeHeight = (relativeHeight + (dy / h) * 2f).coerceIn(0.1f, 0.9f)
                    }
                    Handle.NONE -> {}
                }
                invalidate()
                onMosaicChanged?.invoke(relativeX, relativeY, relativeWidth, relativeHeight, intensity, shape)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandle = Handle.NONE
                onMosaicChanged?.invoke(relativeX, relativeY, relativeWidth, relativeHeight, intensity, shape)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getHitHandle(x: Float, y: Float): Handle {
        val touchRadius = handleRadius * 2f
        if (isNear(x, y, rectF.left, rectF.top, touchRadius)) return Handle.TOP_LEFT
        if (isNear(x, y, rectF.right, rectF.top, touchRadius)) return Handle.TOP_RIGHT
        if (isNear(x, y, rectF.left, rectF.bottom, touchRadius)) return Handle.BOTTOM_LEFT
        if (isNear(x, y, rectF.right, rectF.bottom, touchRadius)) return Handle.BOTTOM_RIGHT
        if (rectF.contains(x, y)) return Handle.BODY
        return Handle.NONE
    }

    private fun isNear(x1: Float, y1: Float, x2: Float, y2: Float, threshold: Float): Boolean {
        val dx = x1 - x2
        val dy = y1 - y2
        return (dx * dx + dy * dy) <= (threshold * threshold)
    }
}
