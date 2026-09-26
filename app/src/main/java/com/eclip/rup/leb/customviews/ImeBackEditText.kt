package com.eclip.rup.leb.customviews

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatEditText

/**
 * An EditText that intercepts the Back key before the Input Method Editor (IME / soft keyboard)
 * consumes it, allowing direct, immediate exit from text editing back to main editor tools.
 */
class ImeBackEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    var onImeBackPressedListener: (() -> Unit)? = null
    var borderStrokeWidth: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var borderStrokeColor: Int = Color.TRANSPARENT
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var isDrawing = false

    override fun invalidate() {
        if (isDrawing) return
        super.invalidate()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun invalidate(dirty: android.graphics.Rect?) {
        if (isDrawing) return
        super.invalidate(dirty)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun invalidate(l: Int, t: Int, r: Int, b: Int) {
        if (isDrawing) return
        super.invalidate(l, t, r, b)
    }

    override fun onDraw(canvas: Canvas) {
        if (borderStrokeWidth > 0f && Color.alpha(borderStrokeColor) > 0 && !text.isNullOrEmpty()) {
            val originalColors = textColors
            val strokePaint = paint
            val originalStyle = strokePaint.style
            val originalStrokeWidth = strokePaint.strokeWidth
            val originalStrokeJoin = strokePaint.strokeJoin
            val originalStrokeCap = strokePaint.strokeCap

            try {
                isDrawing = true

                // 1. Draw outer stroke using the selected border color
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeWidth = borderStrokeWidth
                strokePaint.strokeJoin = Paint.Join.ROUND
                strokePaint.strokeCap = Paint.Cap.ROUND
                setTextColor(borderStrokeColor)
                super.onDraw(canvas)

                // 2. Draw inner text fill using the original text color
                strokePaint.style = Paint.Style.FILL
                strokePaint.strokeWidth = originalStrokeWidth
                strokePaint.strokeJoin = originalStrokeJoin
                strokePaint.strokeCap = originalStrokeCap
                setTextColor(originalColors)
                super.onDraw(canvas)
            } finally {
                strokePaint.style = originalStyle
                strokePaint.strokeWidth = originalStrokeWidth
                strokePaint.strokeJoin = originalStrokeJoin
                strokePaint.strokeCap = originalStrokeCap
                setTextColor(originalColors)
                isDrawing = false
            }
        } else {
            super.onDraw(canvas)
        }
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event != null && onImeBackPressedListener != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val state = keyDispatcherState
                state?.startTracking(event, this)
                return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                val state = keyDispatcherState
                state?.handleUpEvent(event)
                if (state == null || (event.isTracking && !event.isCanceled)) {
                    onImeBackPressedListener?.invoke()
                    return true
                }
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
