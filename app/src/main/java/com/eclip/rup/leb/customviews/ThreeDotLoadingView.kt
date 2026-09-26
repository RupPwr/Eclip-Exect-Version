package com.eclip.rup.leb.customviews

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.annotation.ColorInt
import com.eclip.rup.leb.R
import kotlin.math.sin

/**
 * Global centralized 3-dot sequential blinking loading indicator.
 *
 * Requirements:
 * - Exactly 3 circular dots horizontally aligned.
 * - Sequential blink/pulse: Left -> Middle -> Right -> Repeat.
 * - Fast, smooth animation (~600ms full cycle, ~200ms per dot).
 * - Active dot smoothly pulses/blinks in scale and opacity.
 * - Inactive dots remain in stable, full normal state.
 * - Zero background/card/border/elevation decoration.
 * - Completely lifecycle-aware to prevent memory leaks and unnecessary CPU usage.
 */
class ThreeDotLoadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val DEFAULT_DOT_COUNT = 3
        private const val DEFAULT_DOT_RADIUS_DP = 5.5f
        private const val DEFAULT_DOT_SPACING_DP = 18f
        private const val DEFAULT_DURATION_MS = 600L
    }

    private val dotCount = DEFAULT_DOT_COUNT
    private var dotRadius: Float = dpToPx(DEFAULT_DOT_RADIUS_DP)
    private var dotSpacing: Float = dpToPx(DEFAULT_DOT_SPACING_DP)
    private var animationDuration: Long = DEFAULT_DURATION_MS

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private var animProgress: Float = 0f
    private var animator: ValueAnimator? = null

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.ThreeDotLoadingView, defStyleAttr, 0)
            val color = a.getColor(R.styleable.ThreeDotLoadingView_dotColor, Color.WHITE)
            paint.color = color
            dotRadius = a.getDimension(R.styleable.ThreeDotLoadingView_dotRadius, dotRadius)
            dotSpacing = a.getDimension(R.styleable.ThreeDotLoadingView_dotSpacing, dotSpacing)
            animationDuration = a.getInt(
                R.styleable.ThreeDotLoadingView_animationDuration,
                DEFAULT_DURATION_MS.toInt()
            ).toLong()
            a.recycle()
        }
    }

    private fun dpToPx(dp: Float): Float = dp * context.resources.displayMetrics.density

    fun setDotColor(@ColorInt color: Int) {
        paint.color = color
        invalidate()
    }

    fun setDotRadiusDp(radiusDp: Float) {
        dotRadius = dpToPx(radiusDp)
        requestLayout()
        invalidate()
    }

    fun setDotSpacingDp(spacingDp: Float) {
        dotSpacing = dpToPx(spacingDp)
        requestLayout()
        invalidate()
    }

    fun setAnimationDuration(durationMs: Long) {
        animationDuration = durationMs
        if (animator?.isRunning == true) {
            stopAnimation()
            startAnimation()
        }
    }

    fun show() {
        visibility = VISIBLE
    }

    fun hide() {
        visibility = GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalDotsWidth = (dotCount - 1) * dotSpacing + dotRadius * 2
        val desiredWidth = (totalDotsWidth + paddingLeft + paddingRight).toInt()
        val desiredHeight = (dotRadius * 2 + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        val contentWidth = (dotCount - 1) * dotSpacing
        val startX = paddingLeft + (availableWidth - contentWidth) / 2f
        val cy = paddingTop + availableHeight / 2f

        val activeIndex = animProgress.toInt().coerceIn(0, dotCount - 1)
        val phaseFraction = (animProgress - activeIndex).coerceIn(0f, 1f)

        for (i in 0 until dotCount) {
            val cx = startX + i * dotSpacing
            val (scale, alpha) = if (i == activeIndex) {
                // Smooth sine wave pulse during active phase
                val pulse = sin(phaseFraction * Math.PI).toFloat()
                // Active dot blinks: smoothly dips scale and opacity
                val currentScale = 1.0f - 0.30f * pulse
                val currentAlpha = (255 * (1.0f - 0.75f * pulse)).toInt().coerceIn(0, 255)
                Pair(currentScale, currentAlpha)
            } else {
                // Inactive dots: stable/full state
                Pair(1.0f, 255)
            }

            paint.alpha = alpha
            canvas.drawCircle(cx, cy, dotRadius * scale, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE && windowVisibility == VISIBLE) {
            startAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == VISIBLE) {
            if (isAttachedToWindow) {
                startAnimation()
            }
        } else {
            stopAnimation()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && this.visibility == VISIBLE) {
            startAnimation()
        } else {
            stopAnimation()
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            startAnimation()
        } else {
            stopAnimation()
        }
    }

    private fun startAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, dotCount.toFloat()).apply {
            duration = animationDuration
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                animProgress = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }
}
