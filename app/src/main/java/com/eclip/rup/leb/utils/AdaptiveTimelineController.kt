package com.eclip.rup.leb.utils

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.abs

/**
 * Explicit 3-State Scroll State Machine for Timeline interactions:
 * - [IDLE]: User interaction ended, no motion in progress. Full-quality rendering, thumbnail decoding,
 *   waveform refinement, and single exact preview seek are enabled.
 * - [DRAGGING]: User's finger is actively touching and dragging the timeline. Lightweight rendering is active,
 *   expensive video decoding is de-prioritized/gated.
 * - [SETTLING]: Finger is lifted, but fling / inertia momentum is still scrolling the timeline.
 *   Video playback seek pipeline is strictly SUPPRESSED. Lightweight rendering continues until motion fully stops.
 */
enum class TimelineScrollState {
    IDLE,
    DRAGGING,
    SETTLING
}

/**
 * Adaptive Rendering Modes for Timeline:
 * - [HIGH_FIDELITY]: Detailed thumbnails, full waveform/markers, 60 FPS rendering target.
 * - [HIGH_VELOCITY]: Lightweight placeholders, zero unnecessary thumbnail decoding/generation, throttled frame budget.
 */
enum class TimelineRenderMode {
    HIGH_FIDELITY,
    HIGH_VELOCITY
}

interface AdaptiveTimelineListener {
    fun onScrollStateChanged(oldState: TimelineScrollState, newState: TimelineScrollState) {}
    fun onScrollStateChanged(oldState: TimelineScrollState, newState: TimelineScrollState, generation: Long) {
        onScrollStateChanged(oldState, newState)
    }
    fun onRenderModeChanged(newMode: TimelineRenderMode) {}
    fun onVelocityChanged(velocityPxPerSec: Float) {}
    fun onScrollIdle(currentScrollX: Int) {}
    fun onScrollIdle(currentScrollX: Int, generation: Long) {
        onScrollIdle(currentScrollX)
    }
}

/**
 * AdaptiveTimelineController manages gesture velocity detection, hysteresis thresholding,
 * 3-state scroll state machine (IDLE -> DRAGGING -> SETTLING -> IDLE), rendering quality transitions,
 * generation-based token tracking, seek pipeline gating, and viewport-aware thumbnail restoration.
 */
class AdaptiveTimelineController(
    var highVelocityEnterThreshold: Float = 1000f,
    var highVelocityExitThreshold: Float = 600f,
    private val idleDebounceMs: Long = 80L
) {

    private val mainHandler: Handler? by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Throwable) {
            null
        }
    }
    private val listeners = mutableListOf<AdaptiveTimelineListener>()

    private var velocityTracker: VelocityTracker? = null
    private var lastScrollTimestampMs: Long = 0L
    private var lastScrollX: Int = 0

    var scrollState: TimelineScrollState = TimelineScrollState.IDLE
        private set

    var currentMode: TimelineRenderMode = TimelineRenderMode.HIGH_FIDELITY
        private set

    var currentVelocity: Float = 0f
        private set

    var isTouchActive: Boolean = false
        private set

    var currentGeneration: Long = 1L
        private set

    private val idleRunnable = Runnable {
        if (!isTouchActive) {
            currentVelocity = 0f
            val gen = nextGeneration()
            transitionToState(TimelineScrollState.IDLE)
            transitionToMode(TimelineRenderMode.HIGH_FIDELITY)
            notifyScrollIdle(lastScrollX, gen)
        }
    }

    fun nextGeneration(): Long {
        currentGeneration++
        TimelineLifecycleLogger.currentScrollGeneration = currentGeneration
        return currentGeneration
    }

    fun addListener(listener: AdaptiveTimelineListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            listener.onScrollStateChanged(scrollState, scrollState, currentGeneration)
            listener.onRenderModeChanged(currentMode)
        }
    }

    fun removeListener(listener: AdaptiveTimelineListener) {
        listeners.remove(listener)
    }

    /**
     * Determines whether the video playback / ExoPlayer pipeline is allowed to seek.
     * Rules:
     * - In [TimelineScrollState.SETTLING] (fling inertia): NEVER seek video playback pipeline.
     * - In [TimelineRenderMode.HIGH_VELOCITY]: Suppress seeking to avoid MediaCodec/AudioTrack churn.
     * - In [TimelineScrollState.IDLE]: Seeking is fully enabled (controlled exact single seek).
     * - In slow [TimelineScrollState.DRAGGING]: Allowed only if throttled.
     */
    fun shouldSeekVideoPipeline(): Boolean {
        if (scrollState == TimelineScrollState.SETTLING) {
            return false
        }
        if (currentMode == TimelineRenderMode.HIGH_VELOCITY && scrollState == TimelineScrollState.DRAGGING) {
            return false
        }
        return true
    }

    /**
     * Helper to determine if we should display proxy frames instead of ExoPlayer's output.
     * DRAGGING (fast scrub) state में proxy frames दिखाओ.
     * SETTLING/IDLE में ExoPlayer के real playback को ही render करते रहो.
     */
    fun shouldRenderProxyFrames(): Boolean {
        return scrollState == TimelineScrollState.DRAGGING
    }

    /**
     * Determines the proxy buffer window center offset based on scroll state.
     * DRAGGING state में buffer window का center scrub position के पीछे रखो.
     */
    fun getProxyBufferCenterOffsetMs(): Long {
        return if (scrollState == TimelineScrollState.DRAGGING) -1000L else 0L
    }

    /**
     * Process touch events from the timeline scroll view to track active finger velocity
     * and drive DRAGGING state transitions.
     */
    fun onTouchEvent(event: MotionEvent) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isTouchActive = true
                mainHandler?.removeCallbacks(idleRunnable)
                nextGeneration()
                transitionToState(TimelineScrollState.DRAGGING)
            }
            MotionEvent.ACTION_MOVE -> {
                isTouchActive = true
                mainHandler?.removeCallbacks(idleRunnable)
                if (scrollState != TimelineScrollState.DRAGGING) {
                    transitionToState(TimelineScrollState.DRAGGING)
                }

                velocityTracker?.computeCurrentVelocity(1000) // pixels per second
                val xVel = abs(velocityTracker?.xVelocity ?: 0f)
                updateVelocity(xVel)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouchActive = false
                velocityTracker?.computeCurrentVelocity(1000)
                val xVel = abs(velocityTracker?.xVelocity ?: 0f)
                updateVelocity(xVel)
                velocityTracker?.recycle()
                velocityTracker = null

                // Finger lifted: if motion velocity is non-zero, transition to SETTLING (NOT directly to IDLE!)
                if (xVel >= highVelocityExitThreshold) {
                    nextGeneration()
                    transitionToState(TimelineScrollState.SETTLING)
                    scheduleIdleCheck()
                } else {
                    // Very slow release -> still allow brief debounce before IDLE
                    scheduleIdleCheck()
                }
            }
        }
    }

    /**
     * Process scroll change events to track velocity during inertia/fling even after touch release.
     * When [isProgrammatic] is true (e.g. during video playback auto-scroll), velocity tracking
     * and idle seek scheduling are bypassed to prevent fighting the playback pipeline.
     */
    fun onScrollChanged(scrollX: Int, oldScrollX: Int, isProgrammatic: Boolean = false) {
        val now = System.currentTimeMillis()
        lastScrollX = scrollX

        if (isProgrammatic) {
            lastScrollTimestampMs = now
            currentVelocity = 0f
            return
        }

        val dt = now - lastScrollTimestampMs
        if (dt > 0 && lastScrollTimestampMs > 0) {
            val dx = abs(scrollX - oldScrollX)
            val computedVelocity = (dx.toFloat() / dt.toFloat()) * 1000f // px/sec
            updateVelocity(computedVelocity)

            if (!isTouchActive && computedVelocity > 0f) {
                if (scrollState != TimelineScrollState.SETTLING) {
                    nextGeneration()
                    transitionToState(TimelineScrollState.SETTLING)
                }
            }
        }

        lastScrollTimestampMs = now
        scheduleIdleCheck()
    }

    fun scheduleIdleCheck() {
        mainHandler?.removeCallbacks(idleRunnable)
        mainHandler?.postDelayed(idleRunnable, idleDebounceMs)
    }

    /**
     * Update velocity and apply Hysteresis state transitions.
     */
    private fun updateVelocity(velocity: Float) {
        currentVelocity = velocity
        for (i in 0 until listeners.size) {
            listeners.getOrNull(i)?.onVelocityChanged(velocity)
        }

        when (currentMode) {
            TimelineRenderMode.HIGH_FIDELITY -> {
                if (velocity >= highVelocityEnterThreshold) {
                    transitionToMode(TimelineRenderMode.HIGH_VELOCITY)
                }
            }
            TimelineRenderMode.HIGH_VELOCITY -> {
                if (velocity <= highVelocityExitThreshold && !isTouchActive) {
                    transitionToMode(TimelineRenderMode.HIGH_FIDELITY)
                }
            }
        }
    }

    private fun transitionToState(newState: TimelineScrollState) {
        if (scrollState != newState) {
            val oldState = scrollState
            scrollState = newState
            TimelineLifecycleLogger.logScrollStateChanged(
                oldState,
                newState,
                currentGeneration,
                lastScrollX.toLong(),
                currentVelocity
            )
            for (i in 0 until listeners.size) {
                listeners.getOrNull(i)?.let { listener ->
                    listener.onScrollStateChanged(oldState, newState, currentGeneration)
                    listener.onScrollStateChanged(oldState, newState)
                }
            }
        }
    }

    private fun transitionToMode(newMode: TimelineRenderMode) {
        if (currentMode != newMode) {
            currentMode = newMode
            for (i in 0 until listeners.size) {
                listeners.getOrNull(i)?.onRenderModeChanged(newMode)
            }
        }
    }

    private fun notifyScrollIdle(scrollX: Int, generation: Long) {
        for (i in 0 until listeners.size) {
            listeners.getOrNull(i)?.let { listener ->
                listener.onScrollIdle(scrollX, generation)
                listener.onScrollIdle(scrollX)
            }
        }
    }

    /**
     * Determines whether a scrub/seek action should be throttled based on current render mode.
     * High Fidelity target: ~60 FPS (16ms throttle).
     * High Velocity target: ~30 FPS (33ms throttle) to reduce work on decoding/main threads.
     */
    fun shouldThrottleScrub(lastScrubTimeMs: Long): Boolean {
        val elapsed = System.currentTimeMillis() - lastScrubTimeMs
        val requiredInterval = if (currentMode == TimelineRenderMode.HIGH_VELOCITY) 33L else 16L
        return elapsed < requiredInterval
    }

    /**
     * Helper to compute indices of clips/segments intersecting the visible viewport.
     * @param scrollX Left edge of current scroll viewport.
     * @param viewportWidth Visible width of timeline on screen.
     * @param segmentBounds List of [Pair(leftPx, rightPx)] for all sequence clips.
     * @return List of 0-based clip indices that are visible or partially visible in the viewport.
     */
    fun getVisibleSegmentIndices(
        scrollX: Int,
        viewportWidth: Int,
        segmentBounds: List<Pair<Int, Int>>
    ): List<Int> {
        val viewportLeft = scrollX
        val viewportRight = scrollX + viewportWidth
        val visibleIndices = mutableListOf<Int>()

        for (i in segmentBounds.indices) {
            val (segLeft, segRight) = segmentBounds[i]
            // Overlap check: segRight > viewportLeft and segLeft < viewportRight
            if (segRight > viewportLeft && segLeft < viewportRight) {
                visibleIndices.add(i)
            }
        }
        return visibleIndices
    }

    /**
     * Force immediate transition back to High Fidelity (e.g. when scrubbing finishes or project updates).
     */
    fun forceHighFidelity() {
        mainHandler?.removeCallbacks(idleRunnable)
        currentVelocity = 0f
        val gen = nextGeneration()
        transitionToState(TimelineScrollState.IDLE)
        transitionToMode(TimelineRenderMode.HIGH_FIDELITY)
        notifyScrollIdle(lastScrollX, gen)
    }

    fun release() {
        mainHandler?.removeCallbacks(idleRunnable)
        listeners.clear()
        velocityTracker?.recycle()
        velocityTracker = null
    }
}
