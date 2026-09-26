package com.eclip.rup.leb.customviews

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.eclip.rup.leb.models.EditOperation
import com.eclip.rup.leb.models.TextPosition
import java.io.File

/**
 * TextOverlayView renders text overlays on top of the video player.
 * This allows users to see text operations in real-time without FFmpeg processing.
 */
class TextOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.WHITE
        textSize = 48f
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val density get() = resources.displayMetrics.density
    private var overlayOperations: List<EditOperation> = emptyList()
    private val bitmapCache = mutableMapOf<String, Bitmap>()

    private fun loadBitmapFromUri(uri: android.net.Uri): Bitmap? {
        val cacheKey = uri.toString()
        if (bitmapCache.containsKey(cacheKey)) {
            return bitmapCache[cacheKey]
        }
        try {
            val bitmap = if (uri.scheme == "content") {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            } else {
                val path = uri.path
                if (path != null && File(path).exists()) {
                    BitmapFactory.decodeFile(path)
                } else {
                    null
                }
            }
            if (bitmap != null) {
                bitmapCache[cacheKey] = bitmap
                return bitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private var videoWidth = 0
    private var videoHeight = 0
    var currentPositionMs: Long = 0L
        set(value) {
            field = value
            invalidate()
        }
    var hiddenOperationId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    fun setTextOperations(operations: List<EditOperation.AddText>) {
        this.overlayOperations = operations
        invalidate()
    }

    fun setOverlayOperations(operations: List<EditOperation>) {
        this.overlayOperations = operations
        invalidate()
    }

    fun setVideoSize(width: Int, height: Int) {
        this.videoWidth = width
        this.videoHeight = height
        invalidate()
    }

    private fun getVideoRect(): RectF {
        val rect = RectF()
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            return rect
        }

        val containerRatio = width.toFloat() / height
        val videoRatio = videoWidth.toFloat() / videoHeight

        if (videoRatio > containerRatio) {
            val h = width / videoRatio
            val top = (height - h) / 2f
            rect.set(0f, top, width.toFloat(), top + h)
        } else {
            val w = height * videoRatio
            val left = (width - w) / 2f
            rect.set(left, 0f, left + w, height.toFloat())
        }
        return rect
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val videoRect = getVideoRect()
        val scale = if (videoHeight > 0) videoRect.height() / videoHeight.toFloat() else 1f

        for (op in overlayOperations) {
            if (op is EditOperation.AddText) {
                if (op.id == hiddenOperationId) continue
                val start = op.startTimeMs ?: 0L
                val end = op.endTimeMs ?: Long.MAX_VALUE
                if (currentPositionMs < start || currentPositionMs > end) continue

                paint.textSize = op.fontSize * scale
                val baseTypeface = if (op.fontPath != null) {
                    com.eclip.rup.leb.utils.FontManager.getTypeface(op.fontPath)
                } else {
                    android.graphics.Typeface.DEFAULT
                }
                val tfStyle = (if (op.isBold) android.graphics.Typeface.BOLD else 0) or (if (op.isItalic) android.graphics.Typeface.ITALIC else 0)
                paint.typeface = android.graphics.Typeface.create(baseTypeface, tfStyle)
                paint.isFakeBoldText = op.isBold
                paint.textSkewX = if (op.isItalic) -0.25f else 0f
                if (op.isUnderline) {
                    paint.flags = paint.flags or Paint.UNDERLINE_TEXT_FLAG
                } else {
                    paint.flags = paint.flags and Paint.UNDERLINE_TEXT_FLAG.inv()
                }

                val baseColorInt = try { Color.parseColor(op.color) } catch (e: Exception) { Color.WHITE }
                val alphaInt = (op.opacity * 255).toInt().coerceIn(0, 255)
                paint.color = Color.argb(alphaInt, Color.red(baseColorInt), Color.green(baseColorInt), Color.blue(baseColorInt))
                paint.letterSpacing = op.letterSpacing

                val rawText = when (op.caseMode) {
                    1 -> op.text.uppercase()
                    2 -> op.text.lowercase()
                    else -> op.text
                }
                val maxLineWidth = op.relativeWidth?.let { it * videoRect.width() }
                val lines = getLinesForText(rawText, maxLineWidth, paint)
                val textHeight = (paint.descent() - paint.ascent()) + op.lineSpacing * scale
                val totalHeight = lines.size * textHeight
                
                var maxTextWidth = 0f
                for (line in lines) {
                    val w = paint.measureText(line)
                    if (w > maxTextWidth) maxTextWidth = w
                }

                val startX: Float
                val startY: Float

                val rx = op.relativeX
                val ry = op.relativeY
                val hasCustomPos = rx != null && ry != null

                if (hasCustomPos) {
                    val cx = rx ?: 0.5f
                    val cy = ry ?: 0.5f
                    val centerX = videoRect.left + (cx * videoRect.width())
                    val centerY = videoRect.top + (cy * videoRect.height())
                    startX = centerX - (maxTextWidth / 2f)
                    startY = centerY - (totalHeight / 2f) + (textHeight / 2f) - ((paint.ascent() + paint.descent()) / 2f)
                } else {
                    val rectW = videoRect.width()
                    val rectH = videoRect.height()
                    val rectL = videoRect.left
                    val rectT = videoRect.top

                    when (op.position) {
                        TextPosition.TOP_LEFT -> {
                            startX = rectL + 16f
                            startY = rectT + 32f - paint.ascent()
                        }
                        TextPosition.TOP_CENTER -> {
                            startX = rectL + (rectW - maxTextWidth) / 2
                            startY = rectT + 32f - paint.ascent()
                        }
                        TextPosition.TOP_RIGHT -> {
                            startX = rectL + rectW - maxTextWidth - 16f
                            startY = rectT + 32f - paint.ascent()
                        }
                        TextPosition.CENTER_LEFT -> {
                            startX = rectL + 16f
                            startY = rectT + rectH / 2 - totalHeight / 2 - paint.ascent()
                        }
                        TextPosition.CENTER -> {
                            startX = rectL + (rectW - maxTextWidth) / 2
                            startY = rectT + rectH / 2 - totalHeight / 2 - paint.ascent()
                        }
                        TextPosition.CENTER_RIGHT -> {
                            startX = rectL + rectW - maxTextWidth - 16f
                            startY = rectT + rectH / 2 - totalHeight / 2 - paint.ascent()
                        }
                        TextPosition.BOTTOM_LEFT -> {
                            startX = rectL + 16f
                            startY = rectT + rectH - 16f - totalHeight + textHeight - paint.descent()
                        }
                        TextPosition.BOTTOM_CENTER -> {
                            startX = rectL + (rectW - maxTextWidth) / 2
                            startY = rectT + rectH - 16f - totalHeight + textHeight - paint.descent()
                        }
                        TextPosition.BOTTOM_RIGHT -> {
                            startX = rectL + rectW - maxTextWidth - 16f
                            startY = rectT + rectH - 16f - totalHeight + textHeight - paint.descent()
                        }
                        TextPosition.CENTER_BOTTOM -> {
                            startX = rectL + (rectW - maxTextWidth) / 2
                            startY = rectT + rectH - 16f - totalHeight + textHeight - paint.descent()
                        }
                        TextPosition.CENTER_TOP -> {
                            startX = rectL + (rectW - maxTextWidth) / 2
                            startY = rectT + 32f - paint.ascent()
                        }
                    }
                }

                val centerX = if (hasCustomPos) videoRect.left + ((rx ?: 0.5f) * videoRect.width()) else startX + (maxTextWidth / 2f)
                val centerY = if (hasCustomPos) videoRect.top + ((ry ?: 0.5f) * videoRect.height()) else startY + (totalHeight / 2f)

                val clipDuration = end - start
                val relTime = currentPositionMs - start
                val animDuration = minOf(op.animationDurationMs, clipDuration / 2)

                var animAlpha = 1.0f
                var animScale = 1.0f
                var animTransX = 0f
                var animTransY = 0f

                // In-animation
                if (relTime < animDuration && animDuration > 0) {
                    val progress = (relTime.toFloat() / animDuration).coerceIn(0f, 1f)
                    when (op.inAnimation.uppercase()) {
                        "FADE", "FADE_IN", "FADE IN" -> animAlpha *= progress
                        "ZOOM", "ZOOM_IN", "ZOOM IN" -> {
                            animScale *= (0.3f + 0.7f * progress)
                            animAlpha *= progress
                        }
                        "SLIDE_LEFT", "SLIDE LEFT" -> animTransX += (1f - progress) * (-maxTextWidth)
                        "SLIDE_RIGHT", "SLIDE RIGHT" -> animTransX += (1f - progress) * (maxTextWidth)
                        "SLIDE_UP", "SLIDE UP" -> animTransY += (1f - progress) * (-totalHeight)
                        "SLIDE_DOWN", "SLIDE DOWN" -> animTransY += (1f - progress) * (totalHeight)
                    }
                }

                // Out-animation
                val timeToEnd = end - currentPositionMs
                if (timeToEnd < animDuration && animDuration > 0) {
                    val progress = (timeToEnd.toFloat() / animDuration).coerceIn(0f, 1f)
                    when (op.outAnimation.uppercase()) {
                        "FADE", "FADE_OUT", "FADE OUT" -> animAlpha *= progress
                        "ZOOM", "ZOOM_OUT", "ZOOM OUT" -> {
                            animScale *= (0.3f + 0.7f * progress)
                            animAlpha *= progress
                        }
                        "SLIDE_LEFT", "SLIDE LEFT" -> animTransX += (1f - progress) * (maxTextWidth)
                        "SLIDE_RIGHT", "SLIDE RIGHT" -> animTransX += (1f - progress) * (-maxTextWidth)
                        "SLIDE_UP", "SLIDE UP" -> animTransY += (1f - progress) * (totalHeight)
                        "SLIDE_DOWN", "SLIDE DOWN" -> animTransY += (1f - progress) * (-totalHeight)
                    }
                }

                val finalAlphaInt = (alphaInt * animAlpha).toInt().coerceIn(0, 255)

                canvas.save()
                canvas.translate(animTransX, animTransY)
                if (animScale != 1.0f) {
                    canvas.scale(animScale, animScale, centerX, centerY)
                }
                if (op.rotationAngle != 0f) {
                    canvas.rotate(op.rotationAngle, centerX, centerY)
                }

                // 1. Draw Label background box if specified (Independent from text opacity)
                if (op.labelColor != "NONE" && op.labelColor.isNotBlank() && !op.labelColor.equals("#00000000", ignoreCase = true) && op.labelOpacity > 0f) {
                    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        val baseLabelColor = try { Color.parseColor(op.labelColor) } catch (e: Exception) { Color.TRANSPARENT }
                        val baseAlpha = Color.alpha(baseLabelColor).takeIf { it > 0 } ?: 255
                        val labelAlpha = ((baseAlpha / 255f) * op.labelOpacity * animAlpha * 255).toInt().coerceIn(0, 255)
                        color = Color.argb(labelAlpha, Color.red(baseLabelColor), Color.green(baseLabelColor), Color.blue(baseLabelColor))
                    }
                    val padH = 12f * scale
                    val padV = 6f * scale
                    val bgRect = RectF(
                        startX - padH,
                        startY + paint.ascent() - padV,
                        startX + maxTextWidth + padH,
                        startY + totalHeight - textHeight + paint.descent() + padV
                    )
                    canvas.drawRoundRect(bgRect, 8f * scale, 8f * scale, labelPaint)
                }

                // 2. Configure Shadow on paint if specified (Independent from text opacity)
                if (op.shadowColor != "NONE" && op.shadowColor.isNotBlank() && !op.shadowColor.equals("#00000000", ignoreCase = true) && op.shadowOpacity > 0f) {
                    val shadowColorInt = try { Color.parseColor(op.shadowColor) } catch (e: Exception) { Color.BLACK }
                    val baseAlpha = Color.alpha(shadowColorInt).takeIf { it > 0 } ?: 255
                    val shadowAlpha = ((baseAlpha / 255f) * op.shadowOpacity * animAlpha * 255).toInt().coerceIn(0, 255)
                    val effRadius = if (op.shadowRadius <= 0f) 0.1f else op.shadowRadius
                    paint.setShadowLayer(effRadius * scale, op.shadowDx * scale, op.shadowDy * scale, Color.argb(shadowAlpha, Color.red(shadowColorInt), Color.green(shadowColorInt), Color.blue(shadowColorInt)))
                } else {
                    paint.clearShadowLayer()
                }

                val textAlphaInt = (op.opacity * animAlpha * 255).toInt().coerceIn(0, 255)

                for ((index, line) in lines.withIndex()) {
                    val lineW = paint.measureText(line)
                    val lineX = when (op.textAlign) {
                        "left", "L" -> startX
                        "right", "R" -> startX + maxTextWidth - lineW
                        else -> startX + (maxTextWidth - lineW) / 2
                    }
                    val lineY = startY + (index * textHeight)

                    if (op.borderColor != "NONE" && op.borderColor.isNotBlank() && op.borderOpacity > 0f && op.borderThickness > 0) {
                        paint.style = Paint.Style.STROKE
                        val progress = op.borderThickness.coerceIn(0, 100) / 100f
                        val minStroke = 1.5f * density
                        val maxStroke = 16.0f * density
                        paint.strokeWidth = minStroke + progress * (maxStroke - minStroke)
                        paint.strokeJoin = Paint.Join.ROUND
                        paint.strokeCap = Paint.Cap.ROUND
                        val borderColorInt = try { Color.parseColor(op.borderColor) } catch (e: Exception) { Color.BLACK }
                        val baseAlpha = Color.alpha(borderColorInt).takeIf { it > 0 } ?: 255
                        val borderAlpha = ((baseAlpha / 255f) * op.borderOpacity * animAlpha * 255).toInt().coerceIn(0, 255)
                        paint.color = Color.argb(borderAlpha, Color.red(borderColorInt), Color.green(borderColorInt), Color.blue(borderColorInt))
                        canvas.drawText(line, lineX, lineY, paint)

                        paint.style = Paint.Style.FILL
                        paint.color = Color.argb(textAlphaInt, Color.red(baseColorInt), Color.green(baseColorInt), Color.blue(baseColorInt))
                    } else {
                        paint.style = Paint.Style.FILL
                        paint.color = Color.argb(textAlphaInt, Color.red(baseColorInt), Color.green(baseColorInt), Color.blue(baseColorInt))
                    }
                    
                    canvas.drawText(line, lineX, lineY, paint)
                }
                paint.clearShadowLayer()
                canvas.restore()
            }
        }
    }

    var onTextClicked: ((EditOperation.AddText) -> Unit)? = null

    fun findTextAtPosition(touchX: Float, touchY: Float): EditOperation.AddText? {
        val videoRect = getVideoRect()
        if (videoRect.isEmpty) return null
        val scale = if (videoHeight > 0) videoRect.height() / videoHeight.toFloat() else 1f

        // Search top-to-bottom (reversed order of drawing)
        for (op in overlayOperations.reversed()) {
            if (op is EditOperation.AddText) {
                if (op.id == hiddenOperationId) continue
                val start = op.startTimeMs ?: 0L
                val end = op.endTimeMs ?: Long.MAX_VALUE
                if (currentPositionMs < start || currentPositionMs > end) continue

                paint.textSize = op.fontSize * scale
                val baseTypeface = if (op.fontPath != null) {
                    com.eclip.rup.leb.utils.FontManager.getTypeface(op.fontPath)
                } else {
                    android.graphics.Typeface.DEFAULT
                }
                val tfStyle = (if (op.isBold) android.graphics.Typeface.BOLD else 0) or (if (op.isItalic) android.graphics.Typeface.ITALIC else 0)
                paint.typeface = android.graphics.Typeface.create(baseTypeface, tfStyle)
                paint.isFakeBoldText = op.isBold
                paint.letterSpacing = op.letterSpacing

                val rawText = when (op.caseMode) {
                    1 -> op.text.uppercase()
                    2 -> op.text.lowercase()
                    else -> op.text
                }
                val maxLineWidth = op.relativeWidth?.let { it * videoRect.width() }
                val lines = getLinesForText(rawText, maxLineWidth, paint)
                val textHeight = (paint.descent() - paint.ascent()) + op.lineSpacing * scale
                val totalHeight = lines.size * textHeight
                
                var maxTextWidth = 0f
                for (line in lines) {
                    val w = paint.measureText(line)
                    if (w > maxTextWidth) maxTextWidth = w
                }

                val rx = op.relativeX
                val ry = op.relativeY
                val hasCustomPos = rx != null && ry != null

                val centerX: Float
                val centerY: Float

                if (hasCustomPos) {
                    val cx = rx ?: 0.5f
                    val cy = ry ?: 0.5f
                    centerX = videoRect.left + (cx * videoRect.width())
                    centerY = videoRect.top + (cy * videoRect.height())
                } else {
                    val rectW = videoRect.width()
                    val rectH = videoRect.height()
                    val rectL = videoRect.left
                    val rectT = videoRect.top

                    val startX: Float
                    val startY: Float

                    when (op.position) {
                        TextPosition.TOP_LEFT -> {
                            startX = rectL + 16f
                            startY = rectT + 32f - paint.ascent()
                        }
                        TextPosition.TOP_CENTER -> {
                            startX = rectL + (rectW - maxTextWidth) / 2
                            startY = rectT + 32f - paint.ascent()
                        }
                        TextPosition.TOP_RIGHT -> {
                            startX = rectL + rectW - maxTextWidth - 16f
                            startY = rectT + 32f - paint.ascent()
                        }
                        TextPosition.CENTER_LEFT -> {
                            startX = rectL + 16f
                            startY = rectT + rectH / 2 - totalHeight / 2 - paint.ascent()
                        }
                        TextPosition.CENTER -> {
                            startX = rectL + (rectW - maxTextWidth) / 2
                            startY = rectT + rectH / 2 - totalHeight / 2 - paint.ascent()
                        }
                        TextPosition.CENTER_RIGHT -> {
                            startX = rectL + rectW - maxTextWidth - 16f
                            startY = rectT + rectH / 2 - totalHeight / 2 - paint.ascent()
                        }
                        TextPosition.BOTTOM_LEFT -> {
                            startX = rectL + 16f
                            startY = rectT + rectH - 16f - totalHeight + textHeight - paint.descent()
                        }
                        TextPosition.BOTTOM_CENTER -> {
                            startX = rectL + (rectW - maxTextWidth) / 2
                            startY = rectT + rectH - 16f - totalHeight + textHeight - paint.descent()
                        }
                        TextPosition.BOTTOM_RIGHT -> {
                            startX = rectL + rectW - maxTextWidth - 16f
                            startY = rectT + rectH - 16f - totalHeight + textHeight - paint.descent()
                        }
                        TextPosition.CENTER_BOTTOM -> {
                            startX = rectL + (rectW - maxTextWidth) / 2
                            startY = rectT + rectH - 16f - totalHeight + textHeight - paint.descent()
                        }
                        TextPosition.CENTER_TOP -> {
                            startX = rectL + (rectW - maxTextWidth) / 2
                            startY = rectT + 32f - paint.ascent()
                        }
                    }
                    centerX = startX + (maxTextWidth / 2f)
                    centerY = startY + (totalHeight / 2f)
                }

                // Check rotation unprojected coordinates
                val angleRad = Math.toRadians(-op.rotationAngle.toDouble())
                val cosA = Math.cos(angleRad)
                val sinA = Math.sin(angleRad)
                val dx = touchX - centerX
                val dy = touchY - centerY
                val localX = (dx * cosA - dy * sinA).toFloat()
                val localY = (dx * sinA + dy * cosA).toFloat()

                val touchPadH = 20f * scale + 16f
                val touchPadV = 16f * scale + 16f
                val halfW = (maxTextWidth / 2f) + touchPadH
                val halfH = (totalHeight / 2f) + touchPadV

                if (localX >= -halfW && localX <= halfW && localY >= -halfH && localY <= halfH) {
                    return op
                }
            }
        }
        return null
    }

    private var downX = 0f
    private var downY = 0f
    private var downTargetOp: EditOperation.AddText? = null

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTargetOp = findTextAtPosition(event.x, event.y)
                return downTargetOp != null
            }
            android.view.MotionEvent.ACTION_UP -> {
                val dx = Math.abs(event.x - downX)
                val dy = Math.abs(event.y - downY)
                val slop = 20f
                if (dx <= slop && dy <= slop) {
                    val target = downTargetOp ?: findTextAtPosition(event.x, event.y)
                    if (target != null) {
                        onTextClicked?.invoke(target)
                        downTargetOp = null
                        return true
                    }
                }
                downTargetOp = null
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                downTargetOp = null
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getLinesForText(rawText: String, maxLineWidth: Float?, textPaint: Paint): List<String> {
        val rawLines = rawText.split("\n")
        if (maxLineWidth == null || maxLineWidth <= 0f) {
            return rawLines
        }
        val result = ArrayList<String>()
        for (line in rawLines) {
            if (line.isEmpty()) {
                result.add("")
                continue
            }
            val words = line.split(" ")
            var current = StringBuilder()
            for (word in words) {
                if (current.isEmpty()) {
                    current.append(word)
                } else {
                    val candidate = "$current $word"
                    if (textPaint.measureText(candidate) <= maxLineWidth) {
                        current.append(" ").append(word)
                    } else {
                        result.add(current.toString())
                        current = StringBuilder(word)
                    }
                }
            }
            if (current.isNotEmpty()) {
                result.add(current.toString())
            }
        }
        return result
    }
}
