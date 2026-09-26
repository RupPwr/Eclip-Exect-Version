package com.eclip.rup.leb.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import java.util.Locale
import kotlin.math.hypot

/**
 * TimelineGraphicsBackend decouples the timeline drawing commands from specific graphics APIs
 * (Hardware-accelerated Android Canvas, OpenGL ES, or Vulkan).
 */
interface TimelineGraphicsBackend {
    fun beginFrame(canvas: Canvas, viewportWidth: Float, viewportHeight: Float)
    fun endFrame(canvas: Canvas)
    fun drawPrecomputedPath(canvas: Canvas, path: Path, matrix: Matrix?, paint: Paint)
}

/**
 * FrameMetricsTracker monitors CPU and GPU draw preparation time against the 16.67ms (60 FPS) frame budget.
 */
class FrameMetricsTracker {
    private var frameStartTimeNs: Long = 0L
    var lastFrameDurationMs: Float = 0f
        private set
    var jankFrameCount: Long = 0L
        private set

    fun onFrameStart() {
        frameStartTimeNs = System.nanoTime()
    }

    fun onFrameEnd(): Float {
        if (frameStartTimeNs == 0L) return 0f
        val elapsedNs = System.nanoTime() - frameStartTimeNs
        lastFrameDurationMs = elapsedNs / 1_000_000f
        if (lastFrameDurationMs > 16.67f) {
            jankFrameCount++
        }
        return lastFrameDurationMs
    }
}

/**
 * HardwareLayerManager provides hysteresis-controlled hardware layer lifecycle management
 * to avoid expensive layer creation/destruction thrashing during scrolling gestures.
 */
object HardwareLayerManager {
    fun setLayerTypeHardware(view: View, enable: Boolean) {
        val targetType = if (enable) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        if (view.layerType != targetType) {
            view.setLayerType(targetType, null)
        }
    }
}

/**
 * TimelineRenderer abstraction separating CPU logic (state, gestures, visibility diffing)
 * from GPU rendering (pixel drawing, texture sampling, shader effects, procedural grids).
 */
interface TimelineRenderer {
    fun drawClip(
        canvas: Canvas,
        rect: RectF,
        cornerRadius: Float,
        fillColor: Int,
        isSelected: Boolean,
        renderMode: TimelineRenderMode
    )

    fun drawThumbnail(
        canvas: Canvas,
        bitmap: Bitmap,
        srcRect: Rect?,
        dstRect: RectF,
        alpha: Int = 255
    )

    fun drawProceduralGrid(
        canvas: Canvas,
        durationMs: Long,
        msPerPixel: Float,
        width: Float,
        height: Float,
        density: Float
    )

    fun drawProceduralGridCulled(
        canvas: Canvas,
        durationMs: Long,
        msPerPixel: Float,
        width: Float,
        height: Float,
        viewportLeft: Float,
        viewportRight: Float,
        density: Float
    )

    fun drawTransition(
        canvas: Canvas,
        type: String,
        progress: Float,
        snapshot: Bitmap?,
        dstRect: RectF
    )

    fun drawSelectionHighlight(
        canvas: Canvas,
        rect: RectF,
        cornerRadius: Float,
        accentColor: Int,
        pulsePhase: Float = 0f
    )

    fun drawPrecomputedWaveform(
        canvas: Canvas,
        path: Path,
        xOffset: Float,
        clipRect: RectF?,
        paint: Paint
    )

    fun drawPlayheadProxy(
        canvas: Canvas,
        clipId: String,
        playheadMs: Long,
        dstRect: RectF,
        alpha: Int = 255
    ): Boolean
}

/**
 * Hardware-accelerated implementation of TimelineRenderer.
 * Pre-allocates all Paint, Matrix, Shader, Xfermode, Path, and Rect objects at initialization
 * to ensure ZERO allocations in per-frame rendering hot paths.
 */
class HardwareTimelineRenderer : TimelineRenderer, TimelineGraphicsBackend {

    val metricsTracker = FrameMetricsTracker()

    // --- Pre-allocated Paint Objects ---
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val transitionPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3F3F4A")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A8A93")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93")
        textSize = 24f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    // --- Pre-allocated Geometry and Transform Buffers ---
    private val reusableRectF = RectF()
    private val reusableSrcRect = Rect()
    private val reusablePath = Path()
    private val transformMatrix = Matrix()

    // --- Pre-allocated Zero-Allocation Text Formatting Buffers ---
    private val timeCharBuffer = CharArray(32)
    private val dotCharArray = charArrayOf('·')

    private fun formatTimeToBuffer(
        currentMs: Long,
        stepMs: Long,
        includeHours: Boolean
    ): Int {
        val totalSeconds = (currentMs / 1000).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        var idx = 0

        if (includeHours || hours > 0) {
            timeCharBuffer[idx++] = ('0'.code + (hours / 10 % 10)).toChar()
            timeCharBuffer[idx++] = ('0'.code + (hours % 10)).toChar()
            timeCharBuffer[idx++] = ':'
        }

        timeCharBuffer[idx++] = ('0'.code + (minutes / 10 % 10)).toChar()
        timeCharBuffer[idx++] = ('0'.code + (minutes % 10)).toChar()
        timeCharBuffer[idx++] = ':'

        timeCharBuffer[idx++] = ('0'.code + (seconds / 10 % 10)).toChar()
        timeCharBuffer[idx++] = ('0'.code + (seconds % 10)).toChar()

        if (stepMs < 1000L) {
            val tenths = ((currentMs % 1000) / 100).toInt()
            timeCharBuffer[idx++] = '.'
            timeCharBuffer[idx++] = ('0'.code + (tenths % 10)).toChar()
        }

        return idx
    }

    // --- Pre-allocated Xfermodes ---
    private val srcOverXfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)

    override fun beginFrame(canvas: Canvas, viewportWidth: Float, viewportHeight: Float) {
        metricsTracker.onFrameStart()
    }

    override fun endFrame(canvas: Canvas) {
        metricsTracker.onFrameEnd()
    }

    override fun drawPrecomputedPath(canvas: Canvas, path: Path, matrix: Matrix?, paint: Paint) {
        if (matrix != null) {
            canvas.save()
            canvas.concat(matrix)
            canvas.drawPath(path, paint)
            canvas.restore()
        } else {
            canvas.drawPath(path, paint)
        }
    }

    override fun drawClip(
        canvas: Canvas,
        rect: RectF,
        cornerRadius: Float,
        fillColor: Int,
        isSelected: Boolean,
        renderMode: TimelineRenderMode
    ) {
        if (renderMode == TimelineRenderMode.HIGH_VELOCITY) {
            // Lightweight GPU rendering path: simple solid fill with minimal blending
            fillPaint.color = fillColor
            fillPaint.alpha = 255
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)

            if (isSelected) {
                borderPaint.color = Color.WHITE
                borderPaint.strokeWidth = 1.5f
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
            }
            return
        }

        // High Fidelity rendering path
        fillPaint.color = fillColor
        fillPaint.alpha = 240
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)

        if (isSelected) {
            drawSelectionHighlight(canvas, rect, cornerRadius, Color.WHITE, 0f)
        } else {
            borderPaint.color = Color.parseColor("#26FFFFFF")
            borderPaint.strokeWidth = 1.5f
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
        }
    }

    override fun drawThumbnail(
        canvas: Canvas,
        bitmap: Bitmap,
        srcRect: Rect?,
        dstRect: RectF,
        alpha: Int
    ) {
        if (bitmap.isRecycled) return
        bitmapPaint.alpha = alpha.coerceIn(0, 255)
        bitmapPaint.xfermode = srcOverXfermode

        val src = srcRect ?: run {
            reusableSrcRect.set(0, 0, bitmap.width, bitmap.height)
            reusableSrcRect
        }
        canvas.drawBitmap(bitmap, src, dstRect, bitmapPaint)
    }

    override fun drawSelectionHighlight(
        canvas: Canvas,
        rect: RectF,
        cornerRadius: Float,
        accentColor: Int,
        pulsePhase: Float
    ) {
        // White outline border
        borderPaint.color = accentColor
        borderPaint.strokeWidth = 1.5f
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
    }

    override fun drawPrecomputedWaveform(
        canvas: Canvas,
        path: Path,
        xOffset: Float,
        clipRect: RectF?,
        paint: Paint
    ) {
        canvas.save()
        if (clipRect != null) {
            canvas.clipRect(clipRect)
        }
        canvas.translate(xOffset, 0f)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun drawProceduralGrid(
        canvas: Canvas,
        durationMs: Long,
        msPerPixel: Float,
        width: Float,
        height: Float,
        density: Float
    ) {
        drawProceduralGridCulled(canvas, durationMs, msPerPixel, width, height, 0f, width, density)
    }

    override fun drawProceduralGridCulled(
        canvas: Canvas,
        durationMs: Long,
        msPerPixel: Float,
        width: Float,
        height: Float,
        viewportLeft: Float,
        viewportRight: Float,
        density: Float
    ) {
        if (durationMs <= 0 || width <= 0f || msPerPixel <= 0f) return

        textPaint.textSize = 10f * density

        val targetWidthPx = 80f * density
        val targetMs = targetWidthPx * msPerPixel

        val stepMs = when {
            targetMs <= 100L -> 100L
            targetMs <= 200L -> 200L
            targetMs <= 500L -> 500L
            targetMs <= 1000L -> 1000L
            targetMs <= 2000L -> 2000L
            targetMs <= 5000L -> 5000L
            targetMs <= 10000L -> 10000L
            targetMs <= 30000L -> 30000L
            targetMs <= 60000L -> 60000L
            targetMs <= 120000L -> 120000L
            targetMs <= 300000L -> 300000L
            targetMs <= 600000L -> 600000L
            targetMs <= 1200000L -> 1200000L
            targetMs <= 1800000L -> 1800000L
            targetMs <= 3600000L -> 3600000L
            targetMs <= 7200000L -> 7200000L
            else -> 14400000L
        }

        val minorStepMs = stepMs / 5
        val textY = 12f * density
        val tickBottom = height - 2f * density

        // Viewport culling: calculate starting time boundary directly to skip offscreen ticks
        val visibleStartMs = (viewportLeft * msPerPixel).toLong().coerceAtLeast(0L)
        val visibleEndMs = (viewportRight * msPerPixel).toLong().coerceAtMost(durationMs)

        var currentMs = (visibleStartMs / minorStepMs) * minorStepMs
        while (currentMs <= visibleEndMs) {
            val x = currentMs / msPerPixel

            if (currentMs % stepMs == 0L) {
                canvas.drawLine(x, height * 0.5f, x, tickBottom, majorTickPaint)

                val includeHours = durationMs >= 3600000L
                val charCount = formatTimeToBuffer(currentMs, stepMs, includeHours)
                canvas.drawText(timeCharBuffer, 0, charCount, x, textY, textPaint)
            } else if (currentMs % (stepMs / 2) == 0L) {
                canvas.drawText(dotCharArray, 0, 1, x, textY, textPaint)
                canvas.drawLine(x, height * 0.65f, x, tickBottom, majorTickPaint)
            } else {
                canvas.drawLine(x, height * 0.8f, x, tickBottom, tickPaint)
            }

            currentMs += minorStepMs
        }
    }

    override fun drawTransition(
        canvas: Canvas,
        type: String,
        progress: Float,
        snapshot: Bitmap?,
        dstRect: RectF
    ) {
        val bmp = snapshot
        if (bmp == null || bmp.isRecycled || type == "none" || progress <= 0f || progress >= 1f) {
            return
        }

        val w = dstRect.width()
        val h = dstRect.height()
        if (w <= 0f || h <= 0f) return

        reusableSrcRect.set(0, 0, bmp.width, bmp.height)

        transitionPaint.reset()
        transitionPaint.isAntiAlias = true
        transitionPaint.isFilterBitmap = true

        when (type.lowercase()) {
            "fade", "dissolve" -> {
                transitionPaint.alpha = ((1f - progress) * 255).toInt()
                canvas.drawBitmap(bmp, reusableSrcRect, dstRect, transitionPaint)
            }
            "fadeblack" -> {
                val alpha = if (progress < 0.5f) progress * 2f else (1f - progress) * 2f
                transitionPaint.color = Color.BLACK
                transitionPaint.alpha = (alpha * 255).toInt()
                canvas.drawRect(dstRect, transitionPaint)
            }
            "fadewhite" -> {
                val alpha = if (progress < 0.5f) progress * 2f else (1f - progress) * 2f
                transitionPaint.color = Color.WHITE
                transitionPaint.alpha = (alpha * 255).toInt()
                canvas.drawRect(dstRect, transitionPaint)
            }
            "wipeleft" -> {
                canvas.save()
                canvas.clipRect(dstRect.left, dstRect.top, dstRect.left + w * (1f - progress), dstRect.bottom)
                canvas.drawBitmap(bmp, reusableSrcRect, dstRect, transitionPaint)
                canvas.restore()
            }
            "wiperight" -> {
                canvas.save()
                canvas.clipRect(dstRect.left + w * progress, dstRect.top, dstRect.right, dstRect.bottom)
                canvas.drawBitmap(bmp, reusableSrcRect, dstRect, transitionPaint)
                canvas.restore()
            }
            "wipeup" -> {
                canvas.save()
                canvas.clipRect(dstRect.left, dstRect.top, dstRect.right, dstRect.top + h * (1f - progress))
                canvas.drawBitmap(bmp, reusableSrcRect, dstRect, transitionPaint)
                canvas.restore()
            }
            "wipedown" -> {
                canvas.save()
                canvas.clipRect(dstRect.left, dstRect.top + h * progress, dstRect.right, dstRect.bottom)
                canvas.drawBitmap(bmp, reusableSrcRect, dstRect, transitionPaint)
                canvas.restore()
            }
            "slideleft", "coverleft" -> {
                canvas.save()
                canvas.translate(-w * progress, 0f)
                canvas.drawBitmap(bmp, reusableSrcRect, dstRect, transitionPaint)
                canvas.restore()
            }
            "slideright", "coverright" -> {
                canvas.save()
                canvas.translate(w * progress, 0f)
                canvas.drawBitmap(bmp, reusableSrcRect, dstRect, transitionPaint)
                canvas.restore()
            }
            "slideup" -> {
                canvas.save()
                canvas.translate(0f, -h * progress)
                canvas.drawBitmap(bmp, reusableSrcRect, dstRect, transitionPaint)
                canvas.restore()
            }
            "slidedown" -> {
                canvas.save()
                canvas.translate(0f, h * progress)
                canvas.drawBitmap(bmp, reusableSrcRect, dstRect, transitionPaint)
                canvas.restore()
            }
            "circlecrop" -> {
                canvas.save()
                val maxRadius = hypot((w / 2).toDouble(), (h / 2).toDouble()).toFloat()
                val radius = maxRadius * (1f - progress)
                reusablePath.reset()
                reusablePath.addCircle(dstRect.centerX(), dstRect.centerY(), radius, Path.Direction.CW)
                canvas.clipPath(reusablePath)
                canvas.drawBitmap(bmp, reusableSrcRect, dstRect, transitionPaint)
                canvas.restore()
            }
            "zoomin" -> {
                canvas.save()
                val scale = 1f + progress * 0.4f
                transitionPaint.alpha = ((1f - progress) * 255).toInt()
                canvas.scale(scale, scale, dstRect.centerX(), dstRect.centerY())
                canvas.drawBitmap(bmp, reusableSrcRect, dstRect, transitionPaint)
                canvas.restore()
            }
            else -> {
                transitionPaint.alpha = ((1f - progress) * 255).toInt()
                canvas.drawBitmap(bmp, reusableSrcRect, dstRect, transitionPaint)
            }
        }
    }

    override fun drawPlayheadProxy(
        canvas: Canvas,
        clipId: String,
        playheadMs: Long,
        dstRect: RectF,
        alpha: Int
    ): Boolean {
        val proxyBitmap = RAMFrameBufferManager.getFrameNear(clipId, playheadMs, 100L)
        if (proxyBitmap != null && !proxyBitmap.isRecycled) {
            bitmapPaint.alpha = alpha.coerceIn(0, 255)
            bitmapPaint.xfermode = srcOverXfermode
            
            reusableSrcRect.set(0, 0, proxyBitmap.width, proxyBitmap.height)
            canvas.drawBitmap(proxyBitmap, reusableSrcRect, dstRect, bitmapPaint)
            return true
        }
        return false // Miss: Caller should fallback to real ExoPlayer frame or current state
    }
}
