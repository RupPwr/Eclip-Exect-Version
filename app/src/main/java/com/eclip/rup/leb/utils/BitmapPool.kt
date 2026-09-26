package com.eclip.rup.leb.utils

import android.graphics.Bitmap
import android.os.Build
import android.util.Log

/**
 * BitmapPool provides thread-safe memory reuse for Bitmap pixel buffers,
 * specifically targeting fixed-size timeline thumbnails to eliminate
 * allocation churn and Garbage Collection (GC) pressure.
 *
 * Core Principles:
 * - Reuses compatible mutable Bitmaps via BitmapFactory.Options.inBitmap.
 * - Prevents allocation spikes during high-velocity scrolling and restoration.
 * - Adheres to strict single-ownership rules (only unowned/released Bitmaps are pooled).
 * - Dynamically trims buffers upon Android system memory pressure signals.
 */
object BitmapPool {

    private const val TAG = "BitmapPool"

    // Standard fixed timeline thumbnail metrics (120x90 ARGB_8888 = ~43.2 KB per frame)
    const val THUMBNAIL_WIDTH = 120
    const val THUMBNAIL_HEIGHT = 90
    val DEFAULT_CONFIG: Bitmap.Config = Bitmap.Config.ARGB_8888

    // Memory budget: 1/16th of available max runtime memory (capped between 8MB and 16MB)
    private val maxRuntimeBytes = Runtime.getRuntime().maxMemory()
    private val maxPoolSizeBytes = (maxRuntimeBytes / 16L).coerceIn(8L * 1024 * 1024, 16L * 1024 * 1024)

    private val pool = java.util.concurrent.ConcurrentLinkedDeque<Bitmap>()
    private val currentPoolSizeBytes = java.util.concurrent.atomic.AtomicLong(0L)

    // Tracks active usage count by visible UI views (e.g. FrameAdapter ImageViews)
    private val inUseCounts = java.util.concurrent.ConcurrentHashMap<Bitmap, java.util.concurrent.atomic.AtomicInteger>()
    // Bitmaps whose release back to the pool was deferred because they were still actively bound
    private val pendingReleases = java.util.concurrent.ConcurrentHashMap.newKeySet<Bitmap>()

    val sizeBytes: Long
        get() = currentPoolSizeBytes.get()

    val count: Int
        get() = pool.size

    /**
     * Marks a bitmap as actively in use by a visible view.
     * Prevents the bitmap from being recycled or erased during cache eviction.
     */
    fun markInUse(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        val counter = inUseCounts.getOrPut(bitmap) { java.util.concurrent.atomic.AtomicInteger(0) }
        counter.incrementAndGet()
    }

    /**
     * Decrements the view reference count. When the count reaches zero, if the bitmap was
     * previously deferred for release by LruCache, it is safely returned to the pool now.
     */
    fun releaseFromUse(bitmap: Bitmap?) {
        if (bitmap == null) return
        var shouldReleaseNow = false
        val counter = inUseCounts[bitmap] ?: return
        val current = counter.decrementAndGet()
        
        if (current <= 0) {
            inUseCounts.remove(bitmap)
            if (pendingReleases.remove(bitmap)) {
                shouldReleaseNow = true
            }
        }
        if (shouldReleaseNow) {
            release(bitmap)
        }
    }

    /**
     * Checks if a bitmap is currently marked as in-use by any visible view.
     */
    fun isInUse(bitmap: Bitmap?): Boolean {
        if (bitmap == null) return false
        return (inUseCounts[bitmap]?.get() ?: 0) > 0
    }

    /**
     * Acquires a compatible, mutable Bitmap from the pool if available.
     * Returns null if no compatible Bitmap is currently in the pool.
     */
    fun acquire(
        width: Int = THUMBNAIL_WIDTH,
        height: Int = THUMBNAIL_HEIGHT,
        config: Bitmap.Config = DEFAULT_CONFIG
    ): Bitmap? {
        val iterator = pool.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.isRecycled) {
                iterator.remove()
                continue
            }
            if (isCompatible(candidate, width, height, config)) {
                iterator.remove()
                currentPoolSizeBytes.addAndGet(-candidate.allocationByteCount.toLong())
                // Prepare bitmap for reuse with requested dimensions
                return try {
                    candidate.reconfigure(width, height, config)
                    candidate.eraseColor(android.graphics.Color.TRANSPARENT)
                    candidate
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to reconfigure pooled bitmap (${candidate.width}x${candidate.height}) to ${width}x${height}: ${e.message}")
                    if (!candidate.isRecycled) {
                        try { candidate.recycle() } catch (_: Throwable) {}
                    }
                    try {
                        Bitmap.createBitmap(width, height, config)
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
        }
        return null
    }

    /**
     * Acquires a compatible Bitmap from the pool, or creates a new mutable Bitmap
     * if the pool is empty or lacks a compatible buffer.
     */
    fun acquireOrCreate(
        width: Int = THUMBNAIL_WIDTH,
        height: Int = THUMBNAIL_HEIGHT,
        config: Bitmap.Config = DEFAULT_CONFIG
    ): Bitmap {
        val pooled = acquire(width, height, config)
        if (pooled != null) {
            return pooled
        }
        return Bitmap.createBitmap(width, height, config)
    }

    /**
     * Returns an unowned, mutable Bitmap back to the pool for future reuse.
     * If the Bitmap is recycled, immutable, or if adding it would exceed the pool budget,
     * the Bitmap is discarded safely.
     */
    fun release(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled || !bitmap.isMutable) {
            return
        }

        val bitmapBytes = bitmap.allocationByteCount.toLong()

        // Guard: If this bitmap is actively bound to a visible view, defer pooling/recycling
        if ((inUseCounts[bitmap]?.get() ?: 0) > 0) {
            pendingReleases.add(bitmap)
            return
        }

        // Avoid duplicate additions
        if (pool.contains(bitmap)) {
            return
        }

        // If adding exceeds budget, trim oldest before adding
        while (currentPoolSizeBytes.get() + bitmapBytes > maxPoolSizeBytes && pool.isNotEmpty()) {
            val evicted = pool.poll()
            if (evicted != null) {
                currentPoolSizeBytes.addAndGet(-evicted.allocationByteCount.toLong())
                if (!evicted.isRecycled) {
                    evicted.recycle()
                }
            } else {
                break
            }
        }

        if (currentPoolSizeBytes.get() + bitmapBytes <= maxPoolSizeBytes) {
            pool.addLast(bitmap)
            currentPoolSizeBytes.addAndGet(bitmapBytes)
        } else {
            // Pool cannot accommodate; recycle
            bitmap.recycle()
        }
    }

    /**
     * Releases a collection of Bitmaps back to the pool.
     */
    fun releaseAll(bitmaps: Collection<Bitmap?>) {
        for (bmp in bitmaps) {
            release(bmp)
        }
    }

    /**
     * Checks if a candidate Bitmap can be reused for decoding with the requested dimensions and configuration.
     */
    fun isCompatible(
        candidate: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        targetConfig: Bitmap.Config
    ): Boolean {
        if (candidate.isRecycled || !candidate.isMutable) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // On KitKat and higher, candidate is usable if its allocation byte count
            // is >= required byte count of the decoded image.
            val targetBytes = targetWidth * targetHeight * getBytesPerPixel(targetConfig)
            return candidate.allocationByteCount >= targetBytes
        } else {
            // Below KitKat: exact dimension and configuration match required
            return candidate.width == targetWidth &&
                    candidate.height == targetHeight &&
                    candidate.config == targetConfig
        }
    }

    private fun getBytesPerPixel(config: Bitmap.Config): Int {
        return when (config) {
            Bitmap.Config.ARGB_8888 -> 4
            Bitmap.Config.RGB_565, Bitmap.Config.ARGB_4444 -> 2
            Bitmap.Config.ALPHA_8 -> 1
            else -> 4
        }
    }

    /**
     * Trims the pool down to the specified target byte size.
     */
    fun trim(targetSizeBytes: Long) {
        while (currentPoolSizeBytes.get() > targetSizeBytes && pool.isNotEmpty()) {
            val evicted = pool.poll()
            if (evicted != null) {
                currentPoolSizeBytes.addAndGet(-evicted.allocationByteCount.toLong())
                if (!evicted.isRecycled) {
                    evicted.recycle()
                }
            } else {
                break
            }
        }
    }

    /**
     * Clears and recycles all Bitmaps in the pool.
     */
    fun clear() {
        var bmp = pool.poll()
        while (bmp != null) {
            if (!bmp.isRecycled) {
                bmp.recycle()
            }
            bmp = pool.poll()
        }
        currentPoolSizeBytes.set(0L)
        pendingReleases.clear()
        inUseCounts.clear()
    }
}
