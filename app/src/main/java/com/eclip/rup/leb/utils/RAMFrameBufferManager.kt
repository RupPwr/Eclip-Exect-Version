package com.eclip.rup.leb.utils

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Real-Time RAM Caching System for timeline scrubbing proxy frames (480p).
 * Memory math: 40 frames × (854×480×4 bytes) = ~62.5 MB total budget.
 * Uses a Ring Buffer to limit memory and reuses bitmaps via BitmapPool.
 */
object RAMFrameBufferManager {
    private const val TAG = "RAMFrameBuffer"
    
    // Config: 480p proxy resolution
    const val PROXY_WIDTH = 854
    const val PROXY_HEIGHT = 480
    
    private var maxCapacity = when (DeviceCapabilityManager.ramTier) {
        DeviceCapabilityManager.RamTier.LOW -> 15
        DeviceCapabilityManager.RamTier.MEDIUM -> 25
        DeviceCapabilityManager.RamTier.HIGH -> 40
    }
    
    data class CachedFrame(
        val clipId: String,
        val positionMs: Long,
        val bitmap: Bitmap,
        var lastAccessedMs: Long
    )

    private val cache = ConcurrentHashMap<String, CachedFrame>()
    private val memoryUsedBytes = AtomicLong(0)

    /**
     * Puts a decoded 480p frame into the RAM cache.
     * Overwrites if it already exists, or evicts the oldest frame if capacity is reached.
     */
    @Synchronized
    fun putFrame(clipId: String, positionMs: Long, bitmap: Bitmap) {
        val key = generateKey(clipId, positionMs)
        
        // If it exists, update it
        if (cache.containsKey(key)) {
            val oldFrame = cache[key]
            if (oldFrame?.bitmap != bitmap) {
                oldFrame?.bitmap?.let { BitmapPool.release(it) }
            }
            cache[key] = CachedFrame(clipId, positionMs, bitmap, System.currentTimeMillis())
            return
        }

        // If capacity reached, evict LRU
        if (cache.size >= maxCapacity) {
            evictLRU()
        }

        cache[key] = CachedFrame(clipId, positionMs, bitmap, System.currentTimeMillis())
        memoryUsedBytes.addAndGet(bitmap.allocationByteCount.toLong())
    }

    /**
     * Gets a cached frame near the given position within a tolerance.
     */
    @Synchronized
    fun getFrameNear(clipId: String, positionMs: Long, toleranceMs: Long = 100): Bitmap? {
        val now = System.currentTimeMillis()
        var bestFrame: CachedFrame? = null
        var minDiff = Long.MAX_VALUE

        for ((_, frame) in cache) {
            if (frame.clipId == clipId) {
                val diff = abs(frame.positionMs - positionMs)
                if (diff <= toleranceMs && diff < minDiff) {
                    minDiff = diff
                    bestFrame = frame
                }
            }
        }

        bestFrame?.lastAccessedMs = now
        return bestFrame?.bitmap
    }

    /**
     * Evicts frames outside of a specific time window centered around [centerMs].
     * (playhead se 5 second se dur ke frames turant recycle kar do)
     */
    @Synchronized
    fun evictOutsideWindow(clipId: String, centerMs: Long, windowMs: Long = 5000) {
        val keysToRemove = mutableListOf<String>()
        
        for ((key, frame) in cache) {
            if (frame.clipId == clipId && abs(frame.positionMs - centerMs) > windowMs) {
                keysToRemove.add(key)
            }
        }

        keysToRemove.forEach { key ->
            cache.remove(key)?.let { removedFrame ->
                memoryUsedBytes.addAndGet(-removedFrame.bitmap.allocationByteCount.toLong())
                BitmapPool.release(removedFrame.bitmap)
            }
        }
    }

    @Synchronized
    private fun evictLRU() {
        var oldestKey: String? = null
        var oldestTime = Long.MAX_VALUE
        
        for ((key, frame) in cache) {
            if (frame.lastAccessedMs < oldestTime) {
                oldestTime = frame.lastAccessedMs
                oldestKey = key
            }
        }
        
        oldestKey?.let { key ->
            cache.remove(key)?.let { removedFrame ->
                memoryUsedBytes.addAndGet(-removedFrame.bitmap.allocationByteCount.toLong())
                BitmapPool.release(removedFrame.bitmap)
            }
        }
    }

    /**
     * Reduces the maximum capacity (e.g. from 40 to 20 during RUNNING_LOW).
     */
    @Synchronized
    fun shrinkCapacity(newCapacity: Int) {
        maxCapacity = newCapacity
        while (cache.size > maxCapacity) {
            evictLRU()
        }
        Log.d(TAG, "Capacity shrunk to $newCapacity, current cache size: ${cache.size}")
    }
    
    /**
     * Clears all cached frames, used for emergency and lifecycle events.
     */
    @Synchronized
    fun clearAll() {
        cache.values.forEach { frame ->
            BitmapPool.release(frame.bitmap)
        }
        cache.clear()
        memoryUsedBytes.set(0)
        maxCapacity = when (DeviceCapabilityManager.ramTier) {
            DeviceCapabilityManager.RamTier.LOW -> 15
            DeviceCapabilityManager.RamTier.MEDIUM -> 25
            DeviceCapabilityManager.RamTier.HIGH -> 40
        }
        Log.d(TAG, "All RAM proxy frames cleared.")
    }

    private fun generateKey(clipId: String, positionMs: Long): String {
        return "${clipId}_${positionMs}"
    }
}
