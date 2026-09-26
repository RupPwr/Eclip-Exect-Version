package com.eclip.rup.leb.utils

import android.graphics.Matrix
import android.graphics.RectF

/**
 * Flags for timeline clip properties packed into [PrecomputedTimelineMetadata.flags].
 */
object TimelineClipFlags {
    const val FLAG_NONE = 0
    const val FLAG_SELECTED = 1 shl 0
    const val FLAG_MUTED = 1 shl 1
    const val FLAG_IMAGE = 1 shl 2
    const val FLAG_AUDIO = 1 shl 3
    const val FLAG_TRANSITION_START = 1 shl 4
    const val FLAG_TRANSITION_END = 1 shl 5
    const val FLAG_LOCKED = 1 shl 6
}

/**
 * PrecomputedTimelineMetadata stores timeline clip geometry and temporal layout in compact,
 * contiguous primitive arrays to achieve ZERO-LAYOUT rendering during scrolling, zooming, and fling.
 *
 * Core Principles:
 * 1. Calculate Once: Geometry is built on project load or clip modification, not during frame rendering.
 * 2. Immutable & Allocation-Free: Read-only primitive arrays (LongArray, FloatArray, IntArray) provide
 *    O(1) memory access with zero Garbage Collection (GC) pressure.
 * 3. Fast Viewport Culling: O(log N) binary search instantly identifies visible clip ranges.
 * 4. Coordinate Separation: World Timeline coordinates are separated from Visual Screen transforms.
 */
class PrecomputedTimelineMetadata private constructor(
    val size: Int,
    val totalDurationMs: Long,
    val totalWorldWidth: Float,
    val clipIds: LongArray,
    val startTimes: LongArray,
    val endTimes: LongArray,
    val lefts: FloatArray,
    val rights: FloatArray,
    val widths: FloatArray,
    val trackIndices: IntArray,
    val heights: FloatArray,
    val flags: IntArray
) {

    /**
     * Finds the contiguous index range [firstVisible, lastVisible] of clips that intersect
     * the visible viewport [viewportLeft, viewportRight] using O(log N) binary search.
     *
     * Returns [IntRange.EMPTY] if no clips are visible.
     */
    fun findVisibleRange(viewportLeft: Float, viewportRight: Float): IntRange {
        if (size == 0 || viewportRight < 0f || viewportLeft > totalWorldWidth) {
            return IntRange.EMPTY
        }

        // Binary search for the first clip whose right edge is >= viewportLeft
        var low = 0
        var high = size - 1
        var firstVisible = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (rights[mid] >= viewportLeft) {
                firstVisible = mid
                high = mid - 1 // Search earlier in the array
            } else {
                low = mid + 1
            }
        }

        if (firstVisible == -1 || firstVisible >= size) {
            return IntRange.EMPTY
        }

        // If the first candidate's left edge is already beyond the viewport right, nothing is visible
        if (lefts[firstVisible] > viewportRight) {
            return IntRange.EMPTY
        }

        // Binary search for the last clip whose left edge is <= viewportRight
        low = firstVisible
        high = size - 1
        var lastVisible = firstVisible

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lefts[mid] <= viewportRight) {
                lastVisible = mid
                low = mid + 1 // Search later in the array
            } else {
                high = mid - 1
            }
        }

        return firstVisible..lastVisible
    }

    /**
     * Checks if the clip at index [i] intersects the visible viewport [viewportLeft, viewportRight].
     * O(1) mathematical viewport culling.
     */
    fun isClipVisible(i: Int, viewportLeft: Float, viewportRight: Float): Boolean {
        if (i !in 0 until size) return false
        return rights[i] >= viewportLeft && lefts[i] <= viewportRight
    }

    /**
     * Computes the screen rectangle for clip [i] into the provided [outRectF]
     * using the current [scrollX] and [zoomScale] without creating new RectF instances.
     */
    fun getScreenRect(
        i: Int,
        scrollX: Float,
        zoomScale: Float,
        trackTopY: Float,
        outRectF: RectF
    ) {
        if (i !in 0 until size) {
            outRectF.setEmpty()
            return
        }

        val screenLeft = (lefts[i] - scrollX) * zoomScale
        val screenRight = (rights[i] - scrollX) * zoomScale
        val screenTop = trackTopY
        val screenBottom = trackTopY + heights[i]

        outRectF.set(screenLeft, screenTop, screenRight, screenBottom)
    }

    /**
     * Converts a World X coordinate to Screen X coordinate.
     */
    fun worldToScreenX(worldX: Float, scrollX: Float, zoomScale: Float = 1.0f): Float {
        return (worldX - scrollX) * zoomScale
    }

    /**
     * Converts a Screen X coordinate to World X coordinate.
     */
    fun screenToWorldX(screenX: Float, scrollX: Float, zoomScale: Float = 1.0f): Float {
        return (screenX / zoomScale) + scrollX
    }

    /**
     * Helper to set a transform matrix for canvas operations (Zero-Layout zoom & pan).
     */
    fun applyViewportTransform(matrix: Matrix, scrollX: Float, zoomScale: Float = 1.0f) {
        matrix.reset()
        matrix.postTranslate(-scrollX, 0f)
        if (zoomScale != 1.0f) {
            matrix.postScale(zoomScale, 1.0f)
        }
    }

    fun isFlagSet(index: Int, flag: Int): Boolean {
        if (index !in 0 until size) return false
        return (flags[index] and flag) != 0
    }

    /**
     * Builder for constructing immutable [PrecomputedTimelineMetadata] snapshots.
     */
    class Builder {
        private val clipIds = mutableListOf<Long>()
        private val startTimes = mutableListOf<Long>()
        private val endTimes = mutableListOf<Long>()
        private val lefts = mutableListOf<Float>()
        private val rights = mutableListOf<Float>()
        private val widths = mutableListOf<Float>()
        private val trackIndices = mutableListOf<Int>()
        private val heights = mutableListOf<Float>()
        private val flags = mutableListOf<Int>()

        fun addClip(
            clipId: Long,
            startTimeMs: Long,
            endTimeMs: Long,
            leftPx: Float,
            widthPx: Float,
            trackIndex: Int = 0,
            heightPx: Float = 40f,
            clipFlags: Int = TimelineClipFlags.FLAG_NONE
        ): Builder {
            val rightPx = leftPx + widthPx
            clipIds.add(clipId)
            startTimes.add(startTimeMs)
            endTimes.add(endTimeMs)
            lefts.add(leftPx)
            rights.add(rightPx)
            widths.add(widthPx)
            trackIndices.add(trackIndex)
            heights.add(heightPx)
            flags.add(clipFlags)
            return this
        }

        fun build(): PrecomputedTimelineMetadata {
            val n = clipIds.size
            var maxDuration = 0L
            var maxWidth = 0f

            for (i in 0 until n) {
                if (endTimes[i] > maxDuration) maxDuration = endTimes[i]
                if (rights[i] > maxWidth) maxWidth = rights[i]
            }

            return PrecomputedTimelineMetadata(
                size = n,
                totalDurationMs = maxDuration,
                totalWorldWidth = maxWidth,
                clipIds = clipIds.toLongArray(),
                startTimes = startTimes.toLongArray(),
                endTimes = endTimes.toLongArray(),
                lefts = lefts.toFloatArray(),
                rights = rights.toFloatArray(),
                widths = widths.toFloatArray(),
                trackIndices = trackIndices.toIntArray(),
                heights = heights.toFloatArray(),
                flags = flags.toIntArray()
            )
        }
    }

    companion object {
        val EMPTY = Builder().build()
    }
}
