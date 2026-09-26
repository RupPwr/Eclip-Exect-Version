package com.eclip.rup.leb.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * TimelinePrefetchPipeline implements a predictive conveyor-belt prefetching architecture.
 *
 * It shifts the timeline engine from reactive (loading only after the user arrives) to predictive:
 * 1. Playhead Velocity Prediction: Estimates future positions 2–3s ahead based on playback/scrub velocity.
 * 2. Bi-directional Cushion: Maintains a symmetric buffer (~1500ms / 500px) on both sides of the visible range
 *    to absorb abrupt direction reversals without rendering stalls.
 * 3. Asynchronous Pipeline: Dispatches prefetch tasks to background worker pools without touching the UI thread.
 */
class TimelinePrefetchPipeline<T : Any>(
    private val scope: CoroutineScope = CoroutineScope(AppDispatchers.Thumbnail),
    private val prefetchAction: suspend (T) -> Unit
) {

    private val activePrefetchJobs = ConcurrentHashMap<T, Job>()
    private val prefetchedItems = ConcurrentHashMap.newKeySet<T>()

    private var lastPrefetchTimeMs: Long = 0L
    private val prefetchThrottleMs: Long = 200L

    /**
     * Proactively prefetches upcoming timeline elements based on current playhead position and playback velocity.
     */
    fun onPlayheadAdvanced(
        currentPosMs: Long,
        isPlaying: Boolean,
        playbackSpeed: Float = 1.0f,
        spatialIndex: TimelineSpatialIndex<T>
    ) {
        val now = System.currentTimeMillis()
        if (now - lastPrefetchTimeMs < prefetchThrottleMs) return
        lastPrefetchTimeMs = now

        val isThrottled = ThermalProtectionManager.isThrottled

        // Extrapolate future position
        val forwardHorizonMs = if (isThrottled) {
            if (isPlaying) (500L * playbackSpeed).toLong() else 500L
        } else {
            if (isPlaying) (2500L * playbackSpeed).toLong() else 1500L
        }
        val cushionMs = if (isThrottled) 500L else 1500L

        val rangeStart = (currentPosMs - cushionMs).coerceAtLeast(0L)
        val rangeEnd = currentPosMs + forwardHorizonMs + cushionMs

        val candidateItems = spatialIndex.queryRange(rangeStart, rangeEnd)
        dispatchPrefetch(candidateItems)
    }

    /**
     * Proactively prefetches timeline elements based on scroll velocity and viewport position.
     */
    fun onViewportScrolled(
        scrollX: Int,
        viewportWidth: Int,
        msPerPixel: Float,
        velocityPxPerSec: Float,
        spatialIndex: TimelineSpatialIndex<T>
    ) {
        if (msPerPixel <= 0f || viewportWidth <= 0) return

        val now = System.currentTimeMillis()
        if (now - lastPrefetchTimeMs < prefetchThrottleMs) return
        lastPrefetchTimeMs = now

        val visibleStartMs = (scrollX * msPerPixel).toLong()
        val visibleEndMs = ((scrollX + viewportWidth) * msPerPixel).toLong()

        val isThrottled = ThermalProtectionManager.isThrottled

        // Velocity prediction: extrapolate in direction of velocity
        val velocityOffsetMs = if (isThrottled) {
            0L // Disable velocity prefetch when thermal throttled
        } else {
            (velocityPxPerSec * msPerPixel * 2.0f).toLong()
        }
        val cushionMs = if (isThrottled) {
            (150f * msPerPixel).toLong().coerceAtLeast(500L) // Minimal cushion
        } else {
            (500f * msPerPixel).toLong().coerceAtLeast(1500L) // 500px symmetric cushion
        }

        val minTimeMs: Long
        val maxTimeMs: Long

        if (velocityOffsetMs >= 0) {
            minTimeMs = (visibleStartMs - cushionMs).coerceAtLeast(0L)
            maxTimeMs = visibleEndMs + velocityOffsetMs + cushionMs
        } else {
            minTimeMs = (visibleStartMs + velocityOffsetMs - cushionMs).coerceAtLeast(0L)
            maxTimeMs = visibleEndMs + cushionMs
        }

        val candidateItems = spatialIndex.queryRange(minTimeMs, maxTimeMs)
        dispatchPrefetch(candidateItems)
    }

    private fun dispatchPrefetch(items: List<T>) {
        for (item in items) {
            if (prefetchedItems.contains(item) || activePrefetchJobs.containsKey(item)) {
                continue
            }

            val job = scope.launch {
                try {
                    prefetchAction(item)
                    prefetchedItems.add(item)
                } catch (_: Exception) {
                } finally {
                    activePrefetchJobs.remove(item)
                }
            }
            activePrefetchJobs[item] = job
        }
    }

    /**
     * Invalidates the prefetch tracking cache for a specific item (e.g. when trimmed or modified).
     */
    fun invalidateItem(item: T) {
        activePrefetchJobs.remove(item)?.cancel()
        prefetchedItems.remove(item)
    }

    /**
     * Clears all active prefetch jobs and reset the pipeline.
     */
    fun clear() {
        activePrefetchJobs.values.forEach { it.cancel() }
        activePrefetchJobs.clear()
        prefetchedItems.clear()
    }
}

/**
 * Extension to trigger RAM buffer proxy warm-up during playhead movement.
 */
fun TimelinePrefetchPipeline<*>.warmupProxyBuffer(
    clipId: String,
    sourceFilePath: String,
    currentPosMs: Long,
    scope: CoroutineScope
) {
    // -2s to +3s window for warm-up target
    val startMs = (currentPosMs - 2000L).coerceAtLeast(0L)
    val endMs = currentPosMs + 3000L

    // Free up RAM outside of a 5s radius from current playhead
    RAMFrameBufferManager.evictOutsideWindow(clipId, currentPosMs, 5000L)

    scope.launch {
        ProxyFrameExtractor.extractFramesForWindow(clipId, sourceFilePath, startMs, endMs)
    }
}
