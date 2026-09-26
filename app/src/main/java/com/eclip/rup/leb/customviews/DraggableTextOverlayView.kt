package com.eclip.rup.leb.customviews

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout

/**
 * DraggableTextOverlayView provides an inline, draggable text editing experience
 * directly on top of the video viewport.
 *
 * When activated, it spawns a transparent EditText at the center of the viewport.
 * The user can:
 *   - Type text and see it live on the video canvas
 *   - Drag the text to any position on the viewport
 *   - Adjust font size via external controls (the text editing toolbar)
 *
 * On commit, it reports the text, font size, and fractional position (0.0–1.0)
 * relative to the viewport dimensions — these map directly to FFmpeg drawtext
 * coordinates: x='w*relX':y='h*relY'
 */
class DraggableTextOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ── Public callbacks ──────────────────────────────────────────────────────
    /** Called when the user commits the text (taps Done). */
    var onTextCommitted: ((text: String, fontSize: Int, relativeX: Float, relativeY: Float, relativeWidth: Float?, color: String, fontPath: String?, opacity: Float, borderThickness: Int, borderColor: String, textAlign: String, letterSpacing: Float, lineSpacing: Float, labelColor: String, shadowColor: String, labelOpacity: Float, borderOpacity: Float, shadowOpacity: Float, shadowRadius: Float, shadowDx: Float, shadowDy: Float, isBold: Boolean, isItalic: Boolean, isUnderline: Boolean, caseMode: Int) -> Unit)? = null

    /** Called when the text content changes (for live preview feedback). */
    var onTextChanged: ((text: String) -> Unit)? = null

    /** Called when the user taps the text to edit (keyboard opens). */
    var onEditingFocused: (() -> Unit)? = null
    var onPositionChanged: ((relativeX: Float, relativeY: Float) -> Unit)? = null
    var onPositionCommitted: ((relativeX: Float, relativeY: Float, fontSize: Int, relativeWidth: Float?) -> Unit)? = null

    /** Called when the user presses Back on the software keyboard while editing. */
    var onImeBackPressed: (() -> Unit)? = null

    /** Called when the user taps the top-left X button on the selection box to delete the text. */
    var onDeleteRequested: (() -> Unit)? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var isEditingActive = false
    private var currentFontSize = 36
    private var relativeX = 0.5f  // center default
    private var relativeY = 0.5f
    private var relativeWidth: Float? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var currentColorString = "#FFFFFF"
    private var currentLabelColor = "NONE"
    private var currentBorderColor = "#000000"
    private var currentShadowColor = "NONE"

    private var currentOpacity = 1.0f
    private var currentLabelOpacity = 1.0f
    private var currentBorderOpacity = 1.0f
    private var currentShadowOpacity = 1.0f
    private var currentShadowRadius = 8f
    private var currentShadowDx = 4f
    private var currentShadowDy = 4f
    private var currentBorderThickness = 0
    private var currentTextAlign = "center"
    private var currentLetterSpacing = 0f
    private var currentLineSpacing = 0f
    var currentFontPath: String? = null

    private var isBold = false
    private var isItalic = false
    private var isUnderline = false
    private var caseMode = 0 // 0 = original, 1 = uppercase, 2 = lowercase
    private var rawUserText = ""

    fun setTextColor(colorString: String) {
        currentColorString = colorString
        applyTextStyling()
    }

    fun setLabelColor(colorString: String) {
        currentLabelColor = colorString
        applyTextStyling()
    }

    fun setBorderColor(colorString: String) {
        currentBorderColor = colorString
        if (colorString != "NONE" && currentBorderThickness == 0) {
            currentBorderThickness = 15
        }
        applyTextStyling()
    }

    fun setShadowColor(colorString: String) {
        currentShadowColor = colorString
        applyTextStyling()
    }

    fun setLabelOpacity(opacity: Float) {
        currentLabelOpacity = opacity.coerceIn(0f, 1f)
        applyTextStyling()
    }

    fun setBorderOpacity(opacity: Float) {
        currentBorderOpacity = opacity.coerceIn(0f, 1f)
        applyTextStyling()
    }

    fun setShadowOpacity(opacity: Float) {
        currentShadowOpacity = opacity.coerceIn(0f, 1f)
        applyTextStyling()
    }

    fun setShadowRadius(radius: Float) {
        currentShadowRadius = radius.coerceAtLeast(0f)
        applyTextStyling()
    }

    fun setShadowDx(dx: Float) {
        currentShadowDx = dx
        applyTextStyling()
    }

    fun setShadowDy(dy: Float) {
        currentShadowDy = dy
        applyTextStyling()
    }

    fun setBold(bold: Boolean) {
        isBold = bold
        applyTextFormatting()
    }

    fun setItalic(italic: Boolean) {
        isItalic = italic
        applyTextFormatting()
    }

    fun setUnderline(underline: Boolean) {
        isUnderline = underline
        applyTextFormatting()
    }

    fun setCaseMode(mode: Int) {
        caseMode = mode % 3
        applyTextFormatting()
    }

    fun setFontPath(path: String?) {
        currentFontPath = path
        applyTextFormatting()
    }

    fun getTextColor(): String = currentColorString
    fun getLabelColor(): String = currentLabelColor
    fun getBorderColor(): String = currentBorderColor
    fun getShadowColor(): String = currentShadowColor
    fun getLabelOpacity(): Float = currentLabelOpacity
    fun getBorderOpacity(): Float = currentBorderOpacity
    fun getShadowOpacity(): Float = currentShadowOpacity
    fun getShadowRadius(): Float = currentShadowRadius
    fun getShadowDx(): Float = currentShadowDx
    fun getShadowDy(): Float = currentShadowDy
    fun getBorderThickness(): Int = currentBorderThickness
    fun getBold(): Boolean = isBold
    fun getItalic(): Boolean = isItalic
    fun getUnderline(): Boolean = isUnderline
    fun getCaseMode(): Int = caseMode

    // ── Snap & Drag tracking ──────────────────────────────────────────────────
    var isSnappingEnabled = true
    private var showVerticalGuideline = false
    private var showHorizontalGuideline = false
    private var isDragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private val guidelinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CEC0EC")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
    }

    // ── UI components ─────────────────────────────────────────────────────────
    private val editText: ImeBackEditText = ImeBackEditText(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        setTextColor(Color.WHITE)
        // Text size set in pixels later
        setPadding(16, 8, 16, 8)
        hint = "Type here"
        setHintTextColor(Color.argb(100, 255, 255, 255))
        gravity = Gravity.CENTER
        isSingleLine = false
        maxLines = 5
        isCursorVisible = true

        // Shadow for readability on any video background
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    // ── Density & Dimensions ──────────────────────────────────────────────────
    private val density get() = resources.displayMetrics.density
    private val boxPadding get() = 8f * density

    private val deleteBtnRadius get() = 11f * density
    private val resizeBtnRadius get() = 12f * density
    private val widthHandleRadius get() = 8.5f * density
    private val touchHitRadius get() = 28f * density

    // ── Interaction State ─────────────────────────────────────────────────────
    private enum class TouchTarget {
        NONE, DELETE_BUTTON, RESIZE_HANDLE, WIDTH_HANDLE, DRAG_BOX
    }
    private var activeTouchTarget = TouchTarget.NONE
    private var initialFixedLeft = 0f
    private var initialFixedTop = 0f
    private var initialFontSizeOnResize = 36
    private var initialRelativeWidthOnResize: Float? = null
    private var initialDistanceToAnchor = 1f

    // ── Selection border & handle paints ──────────────────────────────────────
    private val selectionBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.MITER
    }

    private val deleteBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#262628")
        style = Paint.Style.FILL
        setShadowLayer(3f * density, 0f, 1.5f * density, Color.parseColor("#80000000"))
    }

    private val deleteBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
    }

    private val deleteIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val widthHandleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(3f * density, 0f, 1.5f * density, Color.parseColor("#80000000"))
    }

    private val widthHandleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000")
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
    }

    private val widthHandleGripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val accentHandleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2D55") // Vibrant pink/coral accent matching screenshot
        style = Paint.Style.FILL
        setShadowLayer(3f * density, 0f, 1.5f * density, Color.parseColor("#80000000"))
    }

    private val resizeArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val selectionRect = RectF()

    fun setVideoSize(width: Int, height: Int) {
        this.videoWidth = width
        this.videoHeight = height
        post {
            updateFontSizeOnScreen()
            positionEditTextFromRelative()
            invalidate()
        }
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

    private fun updateFontSizeOnScreen() {
        val videoRect = getVideoRect()
        val scale = if (videoHeight > 0) videoRect.height() / videoHeight.toFloat() else 1f
        val fontSizeOnScreen = currentFontSize * scale
        editText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontSizeOnScreen)
        applyTextStyling()
    }

    init {
        // Initially hidden until activated
        visibility = GONE

        // Forward IME back press events
        editText.onImeBackPressedListener = {
            onImeBackPressed?.invoke()
        }

        // Add the EditText as a child
        val editTextParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        addView(editText, editTextParams)

        // Track text changes
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val str = s?.toString() ?: ""
                if (str != getTransformedText(rawUserText)) {
                    rawUserText = str
                }
                onTextChanged?.invoke(str)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                onEditingFocused?.invoke()
            }
        }
        
        editText.setOnClickListener {
            onEditingFocused?.invoke()
        }

        // Allow this view to draw over its children (for the selection border)
        setWillNotDraw(false)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Activate the text editing mode with an optional pre-filled text. */
    fun activate(initialText: String = "", fontSize: Int = 36, fontPath: String? = null) {
        isEditingActive = true
        currentFontSize = fontSize
        relativeX = 0.5f
        relativeY = 0.5f
        relativeWidth = null
        editText.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        currentColorString = "#FFFFFF"
        currentLabelColor = "NONE"
        currentBorderColor = "#000000"
        currentShadowColor = "NONE"
        currentOpacity = 1.0f
        currentLabelOpacity = 1.0f
        currentBorderOpacity = 1.0f
        currentShadowOpacity = 1.0f
        currentShadowRadius = 8f
        currentShadowDx = 4f
        currentShadowDy = 4f
        currentBorderThickness = 0
        currentFontPath = fontPath
        isBold = false
        isItalic = false
        isUnderline = false
        caseMode = 0
        rawUserText = initialText

        editText.setText(initialText)
        applyTextFormatting()
        applyTextStyling()
        updateFontSizeOnScreen()
        visibility = VISIBLE

        // Position at center
        post {
            positionEditTextFromRelative()
            editText.requestFocus()
            showKeyboard()
        }
    }

    /** Activate for re-editing an existing text operation. */
    fun activateForEdit(op: com.eclip.rup.leb.models.EditOperation.AddText) {
        isEditingActive = true
        currentFontSize = op.fontSize
        relativeX = op.relativeX ?: 0.5f
        relativeY = op.relativeY ?: 0.5f
        relativeWidth = op.relativeWidth
        if (op.relativeWidth != null) {
            val videoRect = getVideoRect()
            val wPx = (op.relativeWidth * videoRect.width()).toInt().coerceIn((40f * density).toInt(), videoRect.width().toInt())
            editText.layoutParams = LayoutParams(wPx, LayoutParams.WRAP_CONTENT)
        } else {
            editText.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        currentColorString = op.color
        currentLabelColor = op.labelColor
        currentBorderColor = op.borderColor
        currentShadowColor = op.shadowColor
        currentOpacity = op.opacity
        currentLabelOpacity = op.labelOpacity
        currentBorderOpacity = op.borderOpacity
        currentShadowOpacity = op.shadowOpacity
        currentShadowRadius = op.shadowRadius
        currentShadowDx = op.shadowDx
        currentShadowDy = op.shadowDy
        currentBorderThickness = op.borderThickness
        currentTextAlign = op.textAlign
        currentLetterSpacing = op.letterSpacing
        currentLineSpacing = op.lineSpacing
        currentFontPath = op.fontPath
        isBold = op.isBold
        isItalic = op.isItalic
        isUnderline = op.isUnderline
        caseMode = op.caseMode
        rawUserText = op.text

        editText.setText(getTransformedText(op.text))
        applyTextFormatting()
        applyTextStyling()
        updateFontSizeOnScreen()
        visibility = VISIBLE

        post {
            positionEditTextFromRelative()
            editText.clearFocus()
            hideKeyboard()
        }
    }

    private fun getTransformedText(text: String): String {
        return when (caseMode) {
            1 -> text.uppercase()
            2 -> text.lowercase()
            else -> text
        }
    }

    private fun applyTextFormatting() {
        try {
            val baseTypeface = if (currentFontPath.isNullOrBlank()) {
                Typeface.DEFAULT
            } else {
                com.eclip.rup.leb.utils.FontManager.getTypeface(currentFontPath)
            }
            val style = (if (isBold) Typeface.BOLD else 0) or (if (isItalic) Typeface.ITALIC else 0)
            editText.typeface = Typeface.create(baseTypeface, style)
        } catch (e: Exception) {
            val style = (if (isBold) Typeface.BOLD else 0) or (if (isItalic) Typeface.ITALIC else 0)
            editText.setTypeface(Typeface.DEFAULT, style)
        }

        editText.paint.isFakeBoldText = isBold
        editText.paint.textSkewX = if (isItalic) -0.25f else 0f

        if (isUnderline) {
            editText.paintFlags = editText.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        } else {
            editText.paintFlags = editText.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }

        val currentStr = editText.text?.toString() ?: ""
        if (rawUserText.isEmpty() && currentStr.isNotEmpty()) {
            rawUserText = currentStr
        }
        val transformed = getTransformedText(rawUserText)
        if (currentStr != transformed && transformed.isNotEmpty()) {
            val selStart = editText.selectionStart
            val selEnd = editText.selectionEnd
            editText.setText(transformed)
            if (selStart in 0..transformed.length && selEnd in 0..transformed.length) {
                editText.setSelection(selStart, selEnd)
            } else {
                editText.setSelection(transformed.length)
            }
        }

        post {
            positionEditTextFromRelative()
            invalidate()
        }
    }

    private fun applyTextStyling() {
        // 1. Text color & Opacity (Text-only opacity)
        try {
            val textColorInt = Color.parseColor(currentColorString)
            val alphaInt = (currentOpacity * 255).toInt().coerceIn(0, 255)
            editText.setTextColor(Color.argb(alphaInt, Color.red(textColorInt), Color.green(textColorInt), Color.blue(textColorInt)))
        } catch (e: Exception) {
            editText.setTextColor(Color.WHITE)
        }

        // 2. Label / Background color (Independent from text opacity)
        if (currentLabelColor == "NONE" || currentLabelColor.isBlank() || currentLabelColor.equals("#00000000", ignoreCase = true) || currentLabelOpacity <= 0f) {
            editText.setBackgroundColor(Color.TRANSPARENT)
            if (currentLabelOpacity <= 0f && currentLabelColor != "NONE" && currentLabelColor.isNotBlank()) {
                editText.setPadding(24, 12, 24, 12)
            } else {
                editText.setPadding(16, 8, 16, 8)
            }
        } else {
            try {
                val labelColorInt = Color.parseColor(currentLabelColor)
                val baseLabelAlpha = Color.alpha(labelColorInt).takeIf { it > 0 } ?: 255
                val finalLabelAlpha = ((baseLabelAlpha / 255f) * currentLabelOpacity * 255).toInt().coerceIn(0, 255)
                val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16f
                    setColor(Color.argb(finalLabelAlpha, Color.red(labelColorInt), Color.green(labelColorInt), Color.blue(labelColorInt)))
                }
                editText.background = bgDrawable
                editText.setPadding(24, 12, 24, 12)
            } catch (e: Exception) {
                editText.setBackgroundColor(Color.TRANSPARENT)
                editText.setPadding(16, 8, 16, 8)
            }
        }

        // 3. Shadow (Independent from text opacity)
        if (currentShadowColor != "NONE" && currentShadowColor.isNotBlank() && !currentShadowColor.equals("#00000000", ignoreCase = true) && currentShadowOpacity > 0f) {
            try {
                val shadowColorInt = Color.parseColor(currentShadowColor)
                val baseShadowAlpha = Color.alpha(shadowColorInt).takeIf { it > 0 } ?: 255
                val finalShadowAlpha = ((baseShadowAlpha / 255f) * currentShadowOpacity * 255).toInt().coerceIn(0, 255)
                val effRadius = if (currentShadowRadius <= 0f) 0.1f else currentShadowRadius
                editText.setShadowLayer(effRadius, currentShadowDx, currentShadowDy, Color.argb(finalShadowAlpha, Color.red(shadowColorInt), Color.green(shadowColorInt), Color.blue(shadowColorInt)))
            } catch (e: Exception) {
                editText.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        } else {
            editText.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }

        // 4. Border stroke (Real stroke rendered via ImeBackEditText)
        if (currentBorderColor != "NONE" && currentBorderColor.isNotBlank() && currentBorderOpacity > 0f && currentBorderThickness > 0) {
            try {
                val borderColorInt = Color.parseColor(currentBorderColor)
                val baseBorderAlpha = Color.alpha(borderColorInt).takeIf { it > 0 } ?: 255
                val finalBorderAlpha = ((baseBorderAlpha / 255f) * currentBorderOpacity * 255).toInt().coerceIn(0, 255)
                val progress = currentBorderThickness.coerceIn(0, 100) / 100f
                val minStroke = 1.5f * density
                val maxStroke = 16.0f * density
                val strokePx = minStroke + progress * (maxStroke - minStroke)
                editText.borderStrokeWidth = strokePx
                editText.borderStrokeColor = Color.argb(finalBorderAlpha, Color.red(borderColorInt), Color.green(borderColorInt), Color.blue(borderColorInt))
            } catch (e: Exception) {
                editText.borderStrokeWidth = 0f
                editText.borderStrokeColor = Color.TRANSPARENT
            }
        } else {
            editText.borderStrokeWidth = 0f
            editText.borderStrokeColor = Color.TRANSPARENT
        }

        editText.invalidate()
        post {
            positionEditTextFromRelative()
            invalidate()
        }
    }

    /** Deactivate and hide the text editor. */
    fun deactivate() {
        isEditingActive = false
        visibility = GONE
        editText.clearFocus()
        hideKeyboard()
        editText.setText("")
    }

    /** Expose a way to focus the input field and pop the keyboard. */
    fun requestEditingFocus() {
        editText.requestFocus()
        showKeyboard()
    }

    /** Commit the current text and position, invoke the callback. */
    fun commitText() {
        val text = getTransformedText(rawUserText).trim().ifEmpty { editText.text?.toString()?.trim() ?: "" }
        if (text.isNotEmpty()) {
            // Compute final relative position from EditText's current layout position
            updateRelativePosition()
            onTextCommitted?.invoke(
                text, currentFontSize, relativeX, relativeY, relativeWidth, currentColorString,
                currentFontPath, currentOpacity, currentBorderThickness, currentBorderColor,
                currentTextAlign, currentLetterSpacing, currentLineSpacing,
                currentLabelColor, currentShadowColor,
                currentLabelOpacity, currentBorderOpacity, currentShadowOpacity,
                currentShadowRadius, currentShadowDx, currentShadowDy,
                isBold, isItalic, isUnderline, caseMode
            )
        }
        deactivate()
    }

    /** Get the current text. */
    fun getText(): String = editText.text?.toString() ?: ""

    /** Set the text content from external toolbar input. */
    fun setTextContent(text: String) {
        rawUserText = text
        val transformed = getTransformedText(text)
        if (editText.text?.toString() != transformed) {
            editText.setText(transformed)
            editText.setSelection(transformed.length)
            post {
                positionEditTextFromRelative()
                invalidate()
            }
        }
    }

    /** Update the font size from external toolbar. */
    fun setFontSize(size: Int) {
        currentFontSize = size.coerceIn(8, 500)
        updateFontSizeOnScreen()
        invalidate()
    }

    // New format setters
    fun setOpacity(opacity: Float) {
        currentOpacity = opacity.coerceIn(0f, 1f)
        applyTextStyling()
    }

    fun setBorderThickness(thickness: Int) {
        currentBorderThickness = thickness.coerceIn(0, 100)
        applyTextStyling()
    }

    fun setLetterSpacing(spacing: Float) {
        currentLetterSpacing = spacing
        editText.letterSpacing = spacing
    }

    fun setLineSpacing(spacing: Float) {
        currentLineSpacing = spacing
        editText.setLineSpacing(spacing, 1f)
    }

    fun setTextAlign(align: String) {
        currentTextAlign = align
        when (align) {
            "left", "L" -> editText.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            "right", "R" -> editText.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            else -> editText.gravity = Gravity.CENTER
        }
    }

    /** Increase font size by a step. */
    fun increaseFontSize(step: Int = 2): Int {
        setFontSize(currentFontSize + step)
        return currentFontSize
    }

    /** Decrease font size by a step. */
    fun decreaseFontSize(step: Int = 2): Int {
        setFontSize(currentFontSize - step)
        return currentFontSize
    }

    fun getCurrentFontSize(): Int = currentFontSize

    // ── Touch handling for drag & resize ─────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isEditingActive) return false
        if (ev.pointerCount > 1) {
            activeTouchTarget = TouchTarget.NONE
            return false
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val touchX = ev.x
                val touchY = ev.y

                val curX = editText.translationX
                val curY = editText.translationY
                val curW = editText.width.toFloat()
                val curH = editText.height.toFloat()

                // Update selectionRect first so handles are tested against current visual position
                selectionRect.set(
                    curX - boxPadding,
                    curY - boxPadding,
                    curX + curW + boxPadding,
                    curY + curH + boxPadding
                )

                val deleteCx = selectionRect.left
                val deleteCy = selectionRect.top
                val resizeCx = selectionRect.right
                val resizeCy = selectionRect.bottom
                val widthHandleCx = selectionRect.right
                val widthHandleCy = selectionRect.centerY()

                val deleteHitRadius = 24f * density
                val resizeHitRadius = 28f * density
                val widthHitRadius = 24f * density

                // 1. Check Bottom-Right Resize Button (Strictly on the handle)
                if (Math.hypot((touchX - resizeCx).toDouble(), (touchY - resizeCy).toDouble()) <= resizeHitRadius) {
                    activeTouchTarget = TouchTarget.RESIZE_HANDLE
                    initialFixedLeft = curX
                    initialFixedTop = curY
                    initialFontSizeOnResize = currentFontSize
                    initialRelativeWidthOnResize = relativeWidth

                    val currentW = curW.coerceAtLeast(20f * density)
                    val currentH = curH.coerceAtLeast(20f * density)
                    val initVx = currentW + 2f * boxPadding
                    val initVy = currentH + 2f * boxPadding
                    initialDistanceToAnchor = Math.hypot(initVx.toDouble(), initVy.toDouble()).toFloat().coerceAtLeast(20f * density)
                    return true
                }

                // 2. Check Right-Edge Center Width Handle
                if (Math.hypot((touchX - widthHandleCx).toDouble(), (touchY - widthHandleCy).toDouble()) <= widthHitRadius) {
                    activeTouchTarget = TouchTarget.WIDTH_HANDLE
                    initialFixedLeft = curX
                    initialFixedTop = curY
                    initialRelativeWidthOnResize = relativeWidth ?: (curW / getVideoRect().width())
                    return true
                }

                // 3. Check Top-Left Delete Button
                if (Math.hypot((touchX - deleteCx).toDouble(), (touchY - deleteCy).toDouble()) <= deleteHitRadius) {
                    activeTouchTarget = TouchTarget.DELETE_BUTTON
                    return true
                }

                // 4. Drag Box
                val hitPad = 8f * density
                if (touchX >= selectionRect.left - hitPad && touchX <= selectionRect.right + hitPad &&
                    touchY >= selectionRect.top - hitPad && touchY <= selectionRect.bottom + hitPad) {
                    activeTouchTarget = TouchTarget.DRAG_BOX
                    dragOffsetX = touchX - curX
                    dragOffsetY = touchY - curY
                    return true
                }
                return false
            }
        }
        return false
    }

    /** Check if a given screen-coordinate touch falls on the text box, resize handle, or delete button. */
    fun isTouchOnBoxOrHandles(rawX: Float, rawY: Float): Boolean {
        if (!isEditingActive || visibility != VISIBLE) return false
        val location = IntArray(2)
        getLocationOnScreen(location)
        val touchX = rawX - location[0]
        val touchY = rawY - location[1]

        val curX = editText.translationX
        val curY = editText.translationY
        val curW = editText.width.toFloat()
        val curH = editText.height.toFloat()

        val selRect = RectF(
            curX - boxPadding,
            curY - boxPadding,
            curX + curW + boxPadding,
            curY + curH + boxPadding
        )

        val deleteCx = selRect.left
        val deleteCy = selRect.top
        val resizeCx = selRect.right
        val resizeCy = selRect.bottom
        val widthHandleCx = selRect.right
        val widthHandleCy = selRect.centerY()

        val deleteHitRadius = 24f * density
        val resizeHitRadius = 28f * density
        val widthHitRadius = 24f * density
        val hitPad = 8f * density

        if (Math.hypot((touchX - resizeCx).toDouble(), (touchY - resizeCy).toDouble()) <= resizeHitRadius) return true
        if (Math.hypot((touchX - widthHandleCx).toDouble(), (touchY - widthHandleCy).toDouble()) <= widthHitRadius) return true
        if (Math.hypot((touchX - deleteCx).toDouble(), (touchY - deleteCy).toDouble()) <= deleteHitRadius) return true
        if (touchX >= selRect.left - hitPad && touchX <= selRect.right + hitPad &&
            touchY >= selRect.top - hitPad && touchY <= selRect.bottom + hitPad) return true

        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditingActive) return false
        if (event.pointerCount > 1) {
            activeTouchTarget = TouchTarget.NONE
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val touchX = event.x
                val touchY = event.y

                val curX = editText.translationX
                val curY = editText.translationY
                val curW = editText.width.toFloat()
                val curH = editText.height.toFloat()

                selectionRect.set(
                    curX - boxPadding,
                    curY - boxPadding,
                    curX + curW + boxPadding,
                    curY + curH + boxPadding
                )

                val deleteCx = selectionRect.left
                val deleteCy = selectionRect.top
                val resizeCx = selectionRect.right
                val resizeCy = selectionRect.bottom
                val widthHandleCx = selectionRect.right
                val widthHandleCy = selectionRect.centerY()

                val deleteHitRadius = 24f * density
                val resizeHitRadius = 28f * density
                val widthHitRadius = 24f * density

                if (Math.hypot((touchX - resizeCx).toDouble(), (touchY - resizeCy).toDouble()) <= resizeHitRadius) {
                    activeTouchTarget = TouchTarget.RESIZE_HANDLE
                    initialFixedLeft = curX
                    initialFixedTop = curY
                    initialFontSizeOnResize = currentFontSize
                    initialRelativeWidthOnResize = relativeWidth

                    val currentW = curW.coerceAtLeast(20f * density)
                    val currentH = curH.coerceAtLeast(20f * density)
                    val initVx = currentW + 2f * boxPadding
                    val initVy = currentH + 2f * boxPadding
                    initialDistanceToAnchor = Math.hypot(initVx.toDouble(), initVy.toDouble()).toFloat().coerceAtLeast(20f * density)
                    return true
                }

                if (Math.hypot((touchX - widthHandleCx).toDouble(), (touchY - widthHandleCy).toDouble()) <= widthHitRadius) {
                    activeTouchTarget = TouchTarget.WIDTH_HANDLE
                    initialFixedLeft = curX
                    initialFixedTop = curY
                    initialRelativeWidthOnResize = relativeWidth ?: (curW / getVideoRect().width())
                    return true
                }

                if (Math.hypot((touchX - deleteCx).toDouble(), (touchY - deleteCy).toDouble()) <= deleteHitRadius) {
                    activeTouchTarget = TouchTarget.DELETE_BUTTON
                    return true
                }

                val hitPad = 8f * density
                if (touchX >= selectionRect.left - hitPad && touchX <= selectionRect.right + hitPad &&
                    touchY >= selectionRect.top - hitPad && touchY <= selectionRect.bottom + hitPad) {
                    activeTouchTarget = TouchTarget.DRAG_BOX
                    dragOffsetX = touchX - curX
                    dragOffsetY = touchY - curY
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                when (activeTouchTarget) {
                    TouchTarget.RESIZE_HANDLE -> {
                        val currentVx = (event.x - (initialFixedLeft - boxPadding)).coerceAtLeast(15f * density)
                        val currentVy = (event.y - (initialFixedTop - boxPadding)).coerceAtLeast(15f * density)
                        val currentDistance = Math.hypot(currentVx.toDouble(), currentVy.toDouble()).toFloat()

                        val scaleRatio = (currentDistance / initialDistanceToAnchor).coerceIn(0.15f, 6.0f)

                        val newSize = (initialFontSizeOnResize * scaleRatio).toInt().coerceIn(8, 500)
                        if (newSize != currentFontSize) {
                            currentFontSize = newSize
                            updateFontSizeOnScreen()
                        }

                        if (initialRelativeWidthOnResize != null) {
                            relativeWidth = (initialRelativeWidthOnResize!! * scaleRatio).coerceIn(0.05f, 1.0f)
                            val videoRect = getVideoRect()
                            val wPx = (relativeWidth!! * videoRect.width()).toInt().coerceIn((20f * density).toInt(), videoRect.width().toInt())
                            editText.layoutParams = (editText.layoutParams as LayoutParams).apply {
                                width = wPx
                                height = LayoutParams.WRAP_CONTENT
                                gravity = Gravity.TOP or Gravity.START
                            }
                            val widthSpec = MeasureSpec.makeMeasureSpec(wPx, MeasureSpec.EXACTLY)
                            val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                            editText.measure(widthSpec, heightSpec)
                            editText.layout(0, 0, editText.measuredWidth, editText.measuredHeight)
                        } else {
                            editText.layoutParams = (editText.layoutParams as LayoutParams).apply {
                                width = LayoutParams.WRAP_CONTENT
                                height = LayoutParams.WRAP_CONTENT
                                gravity = Gravity.TOP or Gravity.START
                            }
                            val widthSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                            val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                            editText.measure(widthSpec, heightSpec)
                            editText.layout(0, 0, editText.measuredWidth, editText.measuredHeight)
                        }

                        // Left edge and top position stay strictly locked
                        editText.translationX = initialFixedLeft
                        editText.translationY = initialFixedTop
                        updateRelativePosition()
                        invalidate()
                        return true
                    }

                    TouchTarget.WIDTH_HANDLE -> {
                        val videoRect = getVideoRect()
                        val minWidthPx = 40f * density
                        val maxWidthPx = (videoRect.width() * 0.98f).coerceAtLeast(minWidthPx)
                        val newWidthPx = (event.x - boxPadding - initialFixedLeft).coerceIn(minWidthPx, maxWidthPx)
                        relativeWidth = (newWidthPx / videoRect.width()).coerceIn(0.05f, 1.0f)
                        val wPx = newWidthPx.toInt()

                        editText.layoutParams = (editText.layoutParams as LayoutParams).apply {
                            width = wPx
                            height = LayoutParams.WRAP_CONTENT
                            gravity = Gravity.TOP or Gravity.START
                        }
                        val widthSpec = MeasureSpec.makeMeasureSpec(wPx, MeasureSpec.EXACTLY)
                        val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                        editText.measure(widthSpec, heightSpec)
                        editText.layout(0, 0, editText.measuredWidth, editText.measuredHeight)

                        // Left edge and top position stay strictly locked
                        editText.translationX = initialFixedLeft
                        editText.translationY = initialFixedTop
                        updateRelativePosition()
                        invalidate()
                        return true
                    }

                    TouchTarget.DRAG_BOX -> {
                        val videoRect = getVideoRect()
                        val newX = event.x - dragOffsetX
                        val newY = event.y - dragOffsetY

                        val minX = videoRect.left - editText.width * 0.7f
                        val maxX = videoRect.right - editText.width * 0.3f
                        val minY = videoRect.top - editText.height * 0.7f
                        val maxY = videoRect.bottom - editText.height * 0.3f

                        var targetX = newX.coerceIn(minX, maxX)
                        var targetY = newY.coerceIn(minY, maxY)

                        if (isSnappingEnabled) {
                            val centerX = targetX + editText.width / 2f
                            val centerY = targetY + editText.height / 2f
                            val videoCenterX = videoRect.centerX()
                            val videoCenterY = videoRect.centerY()
                            val threshold = 8f * density

                            if (Math.abs(centerX - videoCenterX) < threshold) {
                                targetX = videoCenterX - editText.width / 2f
                                showVerticalGuideline = true
                            } else {
                                showVerticalGuideline = false
                            }

                            if (Math.abs(centerY - videoCenterY) < threshold) {
                                targetY = videoCenterY - editText.height / 2f
                                showHorizontalGuideline = true
                            } else {
                                showHorizontalGuideline = false
                            }
                        } else {
                            showVerticalGuideline = false
                            showHorizontalGuideline = false
                        }

                        editText.translationX = targetX
                        editText.translationY = targetY
                        updateRelativePosition()
                        invalidate()
                        return true
                    }

                    else -> {}
                }
            }

            MotionEvent.ACTION_UP -> {
                when (activeTouchTarget) {
                    TouchTarget.DELETE_BUTTON -> {
                        val curX = editText.translationX
                        val curY = editText.translationY
                        val deleteCx = curX - boxPadding
                        val deleteCy = curY - boxPadding
                        if (Math.hypot((event.x - deleteCx).toDouble(), (event.y - deleteCy).toDouble()) <= touchHitRadius * 1.5f) {
                            onDeleteRequested?.invoke()
                        }
                    }

                    TouchTarget.DRAG_BOX, TouchTarget.RESIZE_HANDLE, TouchTarget.WIDTH_HANDLE -> {
                        showVerticalGuideline = false
                        showHorizontalGuideline = false
                        updateRelativePosition()
                        invalidate()
                        onPositionCommitted?.invoke(relativeX, relativeY, currentFontSize, relativeWidth)
                    }

                    else -> {}
                }
                activeTouchTarget = TouchTarget.NONE
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                showVerticalGuideline = false
                showHorizontalGuideline = false
                updateRelativePosition()
                invalidate()
                onPositionCommitted?.invoke(relativeX, relativeY, currentFontSize, relativeWidth)
                activeTouchTarget = TouchTarget.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        if (isEditingActive) {
            val videoRect = getVideoRect()
            if (showVerticalGuideline) {
                canvas.drawLine(videoRect.centerX(), videoRect.top, videoRect.centerX(), videoRect.bottom, guidelinePaint)
            }
            if (showHorizontalGuideline) {
                canvas.drawLine(videoRect.left, videoRect.centerY(), videoRect.right, videoRect.centerY(), guidelinePaint)
            }

            // 1. Calculate selection box rect around editText with boxPadding
            val curX = editText.translationX
            val curY = editText.translationY
            val curW = editText.width.toFloat()
            val curH = editText.height.toFloat()

            selectionRect.set(
                curX - boxPadding,
                curY - boxPadding,
                curX + curW + boxPadding,
                curY + curH + boxPadding
            )

            // Draw crisp rectangular selection box with sharp 90-degree square corners
            canvas.drawRect(selectionRect, selectionBoxPaint)

            // 2. Draw Top-Left "X" Delete Button
            val deleteCx = selectionRect.left
            val deleteCy = selectionRect.top
            canvas.drawCircle(deleteCx, deleteCy, deleteBtnRadius, deleteBgPaint)
            canvas.drawCircle(deleteCx, deleteCy, deleteBtnRadius, deleteBorderPaint)
            val crossOffset = 3.8f * density
            canvas.drawLine(deleteCx - crossOffset, deleteCy - crossOffset, deleteCx + crossOffset, deleteCy + crossOffset, deleteIconPaint)
            canvas.drawLine(deleteCx + crossOffset, deleteCy - crossOffset, deleteCx - crossOffset, deleteCy + crossOffset, deleteIconPaint)

            // 3. Draw Right-Edge Center Width Handle (Small Circle Handle)
            val widthHandleCx = selectionRect.right
            val widthHandleCy = selectionRect.centerY()
            canvas.drawCircle(widthHandleCx, widthHandleCy, widthHandleRadius, widthHandleBgPaint)
            canvas.drawCircle(widthHandleCx, widthHandleCy, widthHandleRadius, widthHandleBorderPaint)
            val gripLen = 3.5f * density
            canvas.drawLine(widthHandleCx, widthHandleCy - gripLen, widthHandleCx, widthHandleCy + gripLen, widthHandleGripPaint)

            // 4. Draw Bottom-Right Pink Resize Button with White Double-Ended Diagonal Arrow
            val resizeCx = selectionRect.right
            val resizeCy = selectionRect.bottom
            canvas.drawCircle(resizeCx, resizeCy, resizeBtnRadius, accentHandleBgPaint)

            // Draw white double-ended diagonal arrow (top-left ↖ to bottom-right ↘)
            val arrowOffset = 4.2f * density
            val x1 = resizeCx - arrowOffset
            val y1 = resizeCy - arrowOffset
            val x2 = resizeCx + arrowOffset
            val y2 = resizeCy + arrowOffset

            // Main shaft line
            canvas.drawLine(x1, y1, x2, y2, resizeArrowPaint)

            // Top-left arrowhead (↖)
            val headLen = 3.2f * density
            canvas.drawLine(x1, y1, x1 + headLen, y1, resizeArrowPaint)
            canvas.drawLine(x1, y1, x1, y1 + headLen, resizeArrowPaint)

            // Bottom-right arrowhead (↘)
            canvas.drawLine(x2, y2, x2 - headLen, y2, resizeArrowPaint)
            canvas.drawLine(x2, y2, x2, y2 - headLen, resizeArrowPaint)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun positionEditTextFromRelative() {
        if (width <= 0 || height <= 0) return
        val videoRect = getVideoRect()

        if (relativeWidth != null) {
            val wPx = (relativeWidth!! * videoRect.width()).toInt().coerceIn((40f * density).toInt(), videoRect.width().toInt())
            if (editText.layoutParams.width != wPx) {
                editText.layoutParams = (editText.layoutParams as LayoutParams).apply {
                    width = wPx
                    height = LayoutParams.WRAP_CONTENT
                    gravity = Gravity.TOP or Gravity.START
                }
                val widthSpec = MeasureSpec.makeMeasureSpec(wPx, MeasureSpec.EXACTLY)
                val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                editText.measure(widthSpec, heightSpec)
                editText.layout(0, 0, editText.measuredWidth, editText.measuredHeight)
            }
        }

        val targetCenterX = videoRect.left + (relativeX * videoRect.width())
        val targetCenterY = videoRect.top + (relativeY * videoRect.height())

        editText.translationX = targetCenterX - editText.width / 2f
        editText.translationY = targetCenterY - editText.height / 2f
    }

    private fun updateRelativePosition() {
        if (width <= 0 || height <= 0) return
        val videoRect = getVideoRect()

        val centerX = editText.translationX + editText.width / 2f
        val centerY = editText.translationY + editText.height / 2f

        relativeX = if (videoRect.width() > 0) ((centerX - videoRect.left) / videoRect.width()).coerceIn(0f, 1f) else 0.5f
        relativeY = if (videoRect.height() > 0) ((centerY - videoRect.top) / videoRect.height()).coerceIn(0f, 1f) else 0.5f
        onPositionChanged?.invoke(relativeX, relativeY)
    }

    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editText.windowToken, 0)
    }

    fun getRelativeX(): Float = relativeX
    fun getRelativeY(): Float = relativeY
    fun getOpacity(): Float = currentOpacity

    fun setProperties(rx: Float, ry: Float, op: Float) {
        relativeX = rx
        relativeY = ry
        setOpacity(op)
        post {
            positionEditTextFromRelative()
            invalidate()
        }
    }
}
