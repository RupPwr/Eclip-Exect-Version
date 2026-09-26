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
import com.eclip.rup.leb.utils.performAppHapticFeedback

class TrackTrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var videoDurationMs: Long = 0L
    var maxDurationMs: Long = 0L
    var maxSelectionDurationMs: Long? = null
    var startTimeMs: Long = 0L
    var endTimeMs: Long = 0L
    var activeStartMs: Long = 0L
    var activeEndMs: Long = 0L

    enum class DragTarget { NONE, LEFT, RIGHT, CENTER }

    var onTrimChanged: ((Long, Long, DragTarget) -> Unit)? = null
    var onTrimAdjusting: ((Long, Long) -> Unit)? = null
    var onTrimAdjustingWithDelta: ((Long, Long, Long, Long) -> Unit)? = null
    var onDragStateChanged: ((Boolean) -> Unit)? = null
    var customMsPerPixel: Float? = null

    var trackColor: Int = Color.parseColor("#4285F4") // Default blue
    var trackLabel: String? = null
        set(value) {
            if (field != value) {
                field = value
                cachedWaveformKey = null
                cachedTrackLabel = null
                invalidate()
            }
        }
    var isSelectedTrack: Boolean = false
    var isMainVideoTrack: Boolean = false
    var isTrimEnabled: Boolean = true
    var trackIcon: android.graphics.drawable.Drawable? = null
    var trackThumbnail: android.graphics.Bitmap? = null
    var isAudioTrack: Boolean = false
    var isPlaceholderTrack: Boolean = false
    var onTrackClicked: (() -> Unit)? = null
    
    var minAllowedStartMs: Long = 0L
    var maxAllowedEndMs: Long = 0L
    var minDurationMs: Long = 200L

    var beats: List<Long> = emptyList()
    var internalStartMs: Long = 0L
    var waveformMediaKey: String? = null
        set(value) {
            field = value
            cachedWaveformKey = null
        }
    private val waveformManager = com.eclip.rup.leb.utils.PrecomputedWaveformManager()
    private var cachedWaveformKey: String? = null

    private fun getOrUpdateWaveformKey(): String {
        val directKey = waveformMediaKey
        if (directKey != null) return directKey
        var k = cachedWaveformKey
        if (k == null) {
            k = "wave_${trackLabel ?: "track"}_${videoDurationMs}_${audioAmplitudes?.size ?: 0}"
            cachedWaveformKey = k
        }
        return k
    }

    var audioAmplitudes: FloatArray? = null
        set(value) {
            field = value
            cachedWaveformKey = null
            if (value != null && value.isNotEmpty() && videoDurationMs > 0L) {
                val key = getOrUpdateWaveformKey()
                waveformManager.registerWaveform(key, value, videoDurationMs)
            }
            invalidate()
        }

    var renderMode: com.eclip.rup.leb.utils.TimelineRenderMode = com.eclip.rup.leb.utils.TimelineRenderMode.HIGH_FIDELITY
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val renderer = com.eclip.rup.leb.utils.HardwareTimelineRenderer()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val placeholderTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93")
        isFakeBoldText = false
        textSize = 24f
    }

    private val selectionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }

    private val labelTextPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#252429") // solid color to mask video frames and create a shrinking effect
        style = Paint.Style.FILL
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF") // Semi-transparent white
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val beatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C1E")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val rectF = RectF()
    private val thumbnailRectF = RectF()
    private val leftHandleRectF = RectF()
    private val rightHandleRectF = RectF()
    private val selectionBorderRectF = RectF()
    private val leftHandlePath = android.graphics.Path()
    private val rightHandlePath = android.graphics.Path()
    private val arrowPath = android.graphics.Path()
    private val baseHandleWidthDp = 10f

    // Pre-allocated handle radii to avoid per-frame FloatArray allocation
    private val leftRadii = FloatArray(8)
    private val rightRadii = FloatArray(8)
    private var lastCornerRadius = -1f

    private fun updateHandleRadii(cornerRadius: Float) {
        if (lastCornerRadius == cornerRadius) return
        lastCornerRadius = cornerRadius
        leftRadii[0] = cornerRadius
        leftRadii[1] = cornerRadius
        leftRadii[2] = 0f
        leftRadii[3] = 0f
        leftRadii[4] = 0f
        leftRadii[5] = 0f
        leftRadii[6] = cornerRadius
        leftRadii[7] = cornerRadius

        rightRadii[0] = 0f
        rightRadii[1] = 0f
        rightRadii[2] = cornerRadius
        rightRadii[3] = cornerRadius
        rightRadii[4] = cornerRadius
        rightRadii[5] = cornerRadius
        rightRadii[6] = 0f
        rightRadii[7] = 0f
    }

    // Cached ellipsized label text
    private var cachedTrackLabel: String? = null
    private var cachedMaxTextWidth: Float = -1f
    private var cachedEllipsizedText: String = ""

    private var autoScrollDirection = 0 // -1 for left, 1 for right, 0 for none
    private var autoScrollSpeed = 0f
    private var parentScrollViewRef: android.widget.HorizontalScrollView? = null

    private fun findParentScrollView(): android.widget.HorizontalScrollView? {
        if (parentScrollViewRef != null) return parentScrollViewRef
        var p = parent
        while (p != null) {
            if (p is android.widget.HorizontalScrollView) {
                parentScrollViewRef = p
                return p
            }
            p = p.parent
        }
        return null
    }

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            val parentScroll = findParentScrollView()
            if (isDraggingHandle && autoScrollDirection != 0 && parentScroll != null) {
                val scrollStep = (autoScrollSpeed * autoScrollDirection).toInt()
                if (scrollStep != 0) {
                    val msPerPixel = customMsPerPixel ?: (if (width > 0) videoDurationMs.toFloat() / width else 1f)
                    val dtMs = (scrollStep * msPerPixel).toLong()

                    parentScroll.scrollBy(scrollStep, 0)

                    when (dragTarget) {
                        DragTarget.LEFT -> {
                            val minStart = minAllowedStartMs.coerceAtLeast(0L)
                            val maxStart = endTimeMs - minDurationMs
                            if (maxSelectionDurationMs != null && endTimeMs - (startTimeMs + dtMs) > maxSelectionDurationMs!!) {
                                startTimeMs = endTimeMs - maxSelectionDurationMs!!
                            } else {
                                startTimeMs = (startTimeMs + dtMs).coerceIn(minStart, maxStart.coerceAtLeast(minStart))
                            }
                            onTrimAdjusting?.invoke(startTimeMs, endTimeMs)
                            onTrimAdjustingWithDelta?.invoke(startTimeMs, endTimeMs, dtMs, 0L)
                        }
                        DragTarget.RIGHT -> {
                            val minEnd = startTimeMs + minDurationMs
                            val limit = if (maxAllowedEndMs > 0L) maxAllowedEndMs else (if (maxDurationMs > 0L) maxDurationMs else videoDurationMs)
                            if (maxSelectionDurationMs != null && (endTimeMs + dtMs) - startTimeMs > maxSelectionDurationMs!!) {
                                endTimeMs = startTimeMs + maxSelectionDurationMs!!
                            } else {
                                endTimeMs = (endTimeMs + dtMs).coerceIn(minEnd, limit.coerceAtLeast(minEnd))
                            }
                            onTrimAdjusting?.invoke(startTimeMs, endTimeMs)
                            onTrimAdjustingWithDelta?.invoke(startTimeMs, endTimeMs, 0L, dtMs)
                        }
                        else -> {}
                    }
                    invalidate()
                }
                postOnAnimation(this)
            } else {
                autoScrollDirection = 0
            }
        }
    }

    private fun checkEdgeAutoScroll(event: MotionEvent) {
        val parentScroll = findParentScrollView() ?: return
        val density = resources.displayMetrics.density
        val edgeZone = 44f * density

        val parentLocation = IntArray(2)
        parentScroll.getLocationOnScreen(parentLocation)
        val parentLeftOnScreen = parentLocation[0]
        val parentRightOnScreen = parentLocation[0] + parentScroll.width

        val viewLocation = IntArray(2)
        getLocationOnScreen(viewLocation)
        val rawX = viewLocation[0] + event.x

        when {
            rawX <= parentLeftOnScreen + edgeZone -> {
                val proximity = ((parentLeftOnScreen + edgeZone - rawX) / edgeZone).coerceIn(0f, 1f)
                autoScrollSpeed = (6f + proximity * 18f) * density
                if (autoScrollDirection != -1) {
                    autoScrollDirection = -1
                    removeCallbacks(autoScrollRunnable)
                    postOnAnimation(autoScrollRunnable)
                }
            }
            rawX >= parentRightOnScreen - edgeZone -> {
                val proximity = ((rawX - (parentRightOnScreen - edgeZone)) / edgeZone).coerceIn(0f, 1f)
                autoScrollSpeed = (6f + proximity * 18f) * density
                if (autoScrollDirection != 1) {
                    autoScrollDirection = 1
                    removeCallbacks(autoScrollRunnable)
                    postOnAnimation(autoScrollRunnable)
                }
            }
            else -> {
                stopAutoScroll()
            }
        }
    }

    private fun stopAutoScroll() {
        if (autoScrollDirection != 0) {
            autoScrollDirection = 0
            removeCallbacks(autoScrollRunnable)
        }
    }

    private val touchSlop by lazy {
        android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var lastTouchX = 0f
    private var downRawX = 0f
    private var lastRawX = 0f
    private var hasDisallowedParent = false
    private var dragTarget = DragTarget.NONE
    
    private var isDraggingHandle = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    performAppHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                } else {
                    performAppHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                invalidate()
            }
        }

    fun setRange(videoDurationMs: Long, startTimeMs: Long, endTimeMs: Long) {
        if (this.videoDurationMs != videoDurationMs) {
            cachedWaveformKey = null
        }
        this.videoDurationMs = videoDurationMs
        this.startTimeMs = startTimeMs.coerceIn(0L, videoDurationMs)
        this.endTimeMs = endTimeMs.coerceIn(this.startTimeMs, videoDurationMs)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (videoDurationMs <= 0 || width <= 0) return

        val density = resources.displayMetrics.density
        val cornerRadius = 6f * density
        val msPerPixel = customMsPerPixel ?: (videoDurationMs.toFloat() / width)
        val startX = startTimeMs / msPerPixel
        val endX = endTimeMs / msPerPixel

        rectF.set(startX, 0f, endX, height.toFloat())

        if (isPlaceholderTrack) {
            trackPaint.color = Color.parseColor("#16171E")
            trackPaint.alpha = 255
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, trackPaint)
            
            borderPaint.color = Color.parseColor("#20FFFFFF")
            borderPaint.strokeWidth = 1f * density
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, borderPaint)

            var textStartX = startX + 12f * density
            val iconSize = (height * 0.55f).toInt().coerceIn(12, 20)
            val iconTop = (height - iconSize) / 2

            if (trackIcon != null) {
                trackIcon?.setBounds(textStartX.toInt(), iconTop, (textStartX + iconSize).toInt(), iconTop + iconSize)
                trackIcon?.setTint(Color.parseColor("#8E8E93"))
                trackIcon?.draw(canvas)
                textStartX += iconSize + 6f * density
            }

            if (trackLabel != null) {
                placeholderTextPaint.textSize = 10f * density
                val textY = height / 2f - (placeholderTextPaint.descent() + placeholderTextPaint.ascent()) / 2f
                canvas.drawText(trackLabel!!, textStartX, textY, placeholderTextPaint)
            }
            return
        }
        
        // Draw track fill
        if (!isMainVideoTrack) {
            renderer.drawClip(canvas, rectF, cornerRadius, trackColor, false, renderMode)
        }

        // Draw Audio Wave Background using Precomputed Waveform Architecture
        if (isAudioTrack) {
            canvas.save()
            canvas.clipRect(rectF)
            val centerY = height / 2f
            val maxAmplitude = height * 0.38f
            
            val contentStart = if (isSelectedTrack && isTrimEnabled) startX + baseHandleWidthDp * density else startX
            val contentEnd = if (isSelectedTrack && isTrimEnabled) endX - baseHandleWidthDp * density else endX

            if (audioAmplitudes != null && audioAmplitudes!!.isNotEmpty()) {
                val key = getOrUpdateWaveformKey()
                waveformManager.drawVisibleWaveform(
                    canvas,
                    key,
                    contentStart,
                    contentEnd,
                    0f,
                    width.toFloat(),
                    msPerPixel,
                    height.toFloat(),
                    wavePaint,
                    density
                )
            } else {
                // Fallback to lightweight procedural wave if no data
                var x = contentStart + 2f * density
                val waveSpacing = if (renderMode == com.eclip.rup.leb.utils.TimelineRenderMode.HIGH_VELOCITY) 10f * density else 6f * density
                var timeOffset = 0f
                while (x < contentEnd) {
                    val amplitude = maxAmplitude * (0.3f + 0.7f * Math.abs(Math.sin((x + timeOffset) * 0.05).toFloat()))
                    canvas.drawLine(x, centerY - amplitude, x, centerY + amplitude, wavePaint)
                    x += waveSpacing
                    timeOffset += 1f
                }
            }
            canvas.restore()
        }

        // Draw Beat Markers
        if (beats.isNotEmpty()) {
            canvas.save()
            canvas.clipRect(rectF)
            val beatRadius = 2.5f * density
            val yPos = height - 5f * density
            for (beatTimeMs in beats) {
                val relativeToInternalStart = beatTimeMs - internalStartMs
                if (relativeToInternalStart >= 0) {
                    val beatX = startX + (relativeToInternalStart / msPerPixel)
                    if (beatX <= endX) {
                        canvas.drawCircle(beatX, yPos, beatRadius, beatPaint)
                    }
                }
            }
            canvas.restore()
        }

        // Draw Icon, Thumbnail, and Label if available
        val currentHandleW = if (isSelectedTrack && isTrimEnabled) baseHandleWidthDp * density else 0f
        var textStartX = startX + currentHandleW + 6f * density
        val iconSize = (height * 0.55f).toInt().coerceIn(12, 22)
        val iconTop = (height - iconSize) / 2

        if (trackIcon != null) {
            trackIcon?.setBounds(textStartX.toInt(), iconTop, (textStartX + iconSize).toInt(), iconTop + iconSize)
            trackIcon?.setTint(Color.WHITE)
            trackIcon?.draw(canvas)
            textStartX += iconSize + 6f * density
        }

        if (trackThumbnail != null) {
            val thumbWidth = (iconSize * 1.4f)
            thumbnailRectF.set(textStartX, iconTop.toFloat(), textStartX + thumbWidth, iconTop.toFloat() + iconSize)
            canvas.drawBitmap(trackThumbnail!!, null, thumbnailRectF, trackPaint)
            textStartX += thumbWidth + 6f * density
        }

        if (trackLabel != null) {
            val padding = 8f * density
            val maxTextWidth = (endX - startX) - (textStartX - startX) - currentHandleW - padding
            if (maxTextWidth > 12f) {
                labelTextPaint.textSize = 10f * density
                if (trackLabel != cachedTrackLabel || kotlin.math.abs(maxTextWidth - cachedMaxTextWidth) > 1f) {
                    cachedTrackLabel = trackLabel
                    cachedMaxTextWidth = maxTextWidth
                    cachedEllipsizedText = android.text.TextUtils.ellipsize(
                        trackLabel,
                        labelTextPaint,
                        maxTextWidth,
                        android.text.TextUtils.TruncateAt.END
                    ).toString()
                }
                
                val textY = height / 2f - (labelTextPaint.descent() + labelTextPaint.ascent()) / 2f
                canvas.drawText(cachedEllipsizedText, textStartX, textY, labelTextPaint)
            }
        }

        // Draw Selection Outline and connected Trim Handles (<────────────>)
        if (isSelectedTrack) {
            val strokeW = 1.5f * density
            val currentHandleWidth = if (isTrimEnabled) {
                (baseHandleWidthDp * density).coerceAtMost(((endX - startX) / 2f).coerceAtLeast(0f))
            } else 0f

            selectionBorderPaint.color = Color.WHITE
            selectionBorderPaint.strokeWidth = strokeW
            selectionBorderPaint.style = Paint.Style.STROKE

            if (isTrimEnabled && currentHandleWidth > 0f) {
                val halfStroke = strokeW / 2f

                // 1. Top & bottom connecting thin white outline lines between handles
                val lineStart = startX + currentHandleWidth - 1f
                val lineEnd = endX - currentHandleWidth + 1f
                if (lineEnd > lineStart) {
                    canvas.drawLine(lineStart, halfStroke, lineEnd, halfStroke, selectionBorderPaint)
                    canvas.drawLine(lineStart, height.toFloat() - halfStroke, lineEnd, height.toFloat() - halfStroke, selectionBorderPaint)
                }

                val handleRadius = cornerRadius.coerceAtMost(currentHandleWidth)
                updateHandleRadii(handleRadius)

                // 2. Left Trim Handle (<)
                leftHandlePath.reset()
                leftHandleRectF.set(startX, 0f, startX + currentHandleWidth, height.toFloat())
                leftHandlePath.addRoundRect(leftHandleRectF, leftRadii, android.graphics.Path.Direction.CW)
                handlePaint.color = Color.WHITE
                handlePaint.style = Paint.Style.FILL
                canvas.drawPath(leftHandlePath, handlePaint)

                // Left Chevron Arrow (<)
                if (currentHandleWidth > 4f * density) {
                    arrowPaint.strokeWidth = 1.6f * density
                    arrowPaint.color = Color.parseColor("#1C1C1E")
                    val arrowSize = 3.5f * density
                    val leftMidX = startX + currentHandleWidth / 2f
                    val midY = height / 2f
                    arrowPath.reset()
                    arrowPath.moveTo(leftMidX + arrowSize * 0.45f, midY - arrowSize)
                    arrowPath.lineTo(leftMidX - arrowSize * 0.45f, midY)
                    arrowPath.lineTo(leftMidX + arrowSize * 0.45f, midY + arrowSize)
                    canvas.drawPath(arrowPath, arrowPaint)
                }

                // 3. Right Trim Handle (>)
                rightHandlePath.reset()
                rightHandleRectF.set(endX - currentHandleWidth, 0f, endX, height.toFloat())
                rightHandlePath.addRoundRect(rightHandleRectF, rightRadii, android.graphics.Path.Direction.CW)
                canvas.drawPath(rightHandlePath, handlePaint)

                // Right Chevron Arrow (>)
                if (currentHandleWidth > 4f * density) {
                    val rightMidX = endX - currentHandleWidth / 2f
                    val midY = height / 2f
                    val arrowSize = 3.5f * density
                    arrowPath.reset()
                    arrowPath.moveTo(rightMidX - arrowSize * 0.45f, midY - arrowSize)
                    arrowPath.lineTo(rightMidX + arrowSize * 0.45f, midY)
                    arrowPath.lineTo(rightMidX - arrowSize * 0.45f, midY + arrowSize)
                    canvas.drawPath(arrowPath, arrowPaint)
                }
            } else {
                // If trimming not enabled, draw complete thin selection outline
                val halfStroke = strokeW / 2f
                selectionBorderRectF.set(startX + halfStroke, halfStroke, endX - halfStroke, height.toFloat() - halfStroke)
                canvas.drawRoundRect(selectionBorderRectF, cornerRadius, cornerRadius, selectionBorderPaint)
            }
        } else {
            // Subtle unselected outline
            borderPaint.color = Color.parseColor("#26FFFFFF")
            borderPaint.strokeWidth = 1f * density
            val halfStroke = (1f * density) / 2f
            selectionBorderRectF.set(startX + halfStroke, halfStroke, endX - halfStroke, height.toFloat() - halfStroke)
            canvas.drawRoundRect(selectionBorderRectF, cornerRadius, cornerRadius, borderPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (videoDurationMs <= 0) return false

        val msPerPixel = customMsPerPixel ?: (videoDurationMs.toFloat() / width)
        val startX = startTimeMs / msPerPixel
        val endX = endTimeMs / msPerPixel
        val density = resources.displayMetrics.density
        val baseHandleWidth = (baseHandleWidthDp * density).coerceAtMost(((endX - startX) / 2f).coerceAtLeast(0f))
        val handleTouchMargin = 22f * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTouchX = event.x
                downTouchY = event.y
                lastTouchX = event.x
                downRawX = event.rawX
                lastRawX = event.rawX
                hasDisallowedParent = false
                isDraggingHandle = false
                stopAutoScroll()

                val distToLeft = Math.abs(event.x - (startX + baseHandleWidth / 2f))
                val distToRight = Math.abs(event.x - (endX - baseHandleWidth / 2f))
                val effectiveTouchMargin = 28f * density
                val nearLeft = distToLeft <= baseHandleWidth / 2f + effectiveTouchMargin
                val nearRight = distToRight <= baseHandleWidth / 2f + effectiveTouchMargin

                dragTarget = if (isTrimEnabled && (nearLeft || nearRight)) {
                    if (nearLeft && nearRight) {
                        if (distToLeft <= distToRight) DragTarget.LEFT else DragTarget.RIGHT
                    } else if (nearLeft) {
                        DragTarget.LEFT
                    } else {
                        DragTarget.RIGHT
                    }
                } else {
                    DragTarget.CENTER
                }
                // Return true so we receive MOVE/UP, but do NOT disallow intercept here so parent HorizontalScrollView / ScrollView can scroll freely.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.y - downTouchY
                val absDx = Math.abs(dx)
                val absDy = Math.abs(dy)
                val stepDx = event.rawX - lastRawX
                val dtMs = (stepDx * msPerPixel).toLong()

                if (isTrimEnabled && (dragTarget == DragTarget.LEFT || dragTarget == DragTarget.RIGHT)) {
                    if (absDx > touchSlop && absDx > absDy) {
                        // User is dragging a trim handle horizontally!
                        if (!hasDisallowedParent) {
                            hasDisallowedParent = true
                            isDraggingHandle = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            onDragStateChanged?.invoke(true)
                        }

                        when (dragTarget) {
                            DragTarget.LEFT -> {
                                val minStart = minAllowedStartMs.coerceAtLeast(0L)
                                val maxStart = endTimeMs - minDurationMs
                                if (maxSelectionDurationMs != null && endTimeMs - (startTimeMs + dtMs) > maxSelectionDurationMs!!) {
                                    startTimeMs = endTimeMs - maxSelectionDurationMs!!
                                } else {
                                    startTimeMs = (startTimeMs + dtMs).coerceIn(minStart, maxStart.coerceAtLeast(minStart))
                                }
                            }
                            DragTarget.RIGHT -> {
                                val minEnd = startTimeMs + minDurationMs
                                val limit = if (maxAllowedEndMs > 0L) maxAllowedEndMs else (if (maxDurationMs > 0L) maxDurationMs else videoDurationMs)
                                if (maxSelectionDurationMs != null && (endTimeMs + dtMs) - startTimeMs > maxSelectionDurationMs!!) {
                                    endTimeMs = startTimeMs + maxSelectionDurationMs!!
                                } else {
                                    endTimeMs = (endTimeMs + dtMs).coerceIn(minEnd, limit.coerceAtLeast(minEnd))
                                }
                            }
                            else -> {}
                        }

                        lastRawX = event.rawX
                        lastTouchX = event.x
                        invalidate()
                        onTrimAdjusting?.invoke(startTimeMs, endTimeMs)
                        onTrimAdjustingWithDelta?.invoke(
                            startTimeMs,
                            endTimeMs,
                            if (dragTarget == DragTarget.LEFT) dtMs else 0L,
                            if (dragTarget == DragTarget.RIGHT) dtMs else 0L
                        )

                        // Check screen edge auto-scrolling
                        checkEdgeAutoScroll(event)
                        return true
                    } else if (absDy > touchSlop && absDy > absDx && !hasDisallowedParent) {
                        // User is swiping vertically -> let ScrollView intercept
                        stopAutoScroll()
                        return false
                    }
                    return true
                } else {
                    // Body touch: keep receiving until parent HorizontalScrollView or ScrollView intercepts
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                stopAutoScroll()
                val dx = event.rawX - downRawX
                val dy = event.y - downTouchY
                val absDx = Math.abs(dx)
                val absDy = Math.abs(dy)

                if (hasDisallowedParent && isDraggingHandle) {
                    onTrimChanged?.invoke(startTimeMs, endTimeMs, dragTarget)
                } else if (!isDraggingHandle && absDx < touchSlop && absDy < touchSlop) {
                    // Tap on track
                    performClick()
                    onTrackClicked?.invoke()
                }

                if (hasDisallowedParent) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onDragStateChanged?.invoke(false)
                }
                dragTarget = DragTarget.NONE
                isDraggingHandle = false
                hasDisallowedParent = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                stopAutoScroll()
                if (hasDisallowedParent) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onDragStateChanged?.invoke(false)
                }
                dragTarget = DragTarget.NONE
                isDraggingHandle = false
                hasDisallowedParent = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        // Safety reset: if view is detached while drag is in progress, notify the callback
        // so isTrackDragging doesn't get stuck true in the host Activity.
        stopAutoScroll()
        if (hasDisallowedParent || isDraggingHandle) {
            parent?.requestDisallowInterceptTouchEvent(false)
            onDragStateChanged?.invoke(false)
            isDraggingHandle = false
            hasDisallowedParent = false
            dragTarget = DragTarget.NONE
        }
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
