package com.eclip.rup.leb.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * ThumbnailCacheManager provides a Two-Tier Caching strategy for timeline thumbnails:
 * 1. Memory Cache (LruCache): In-memory decoded Bitmaps sized dynamically (1/8th max RAM).
 * 2. Disk Cache: Compressed WebP images stored in cacheDir/thumbnails for instant persistence across app restarts.
 * 3. Fresh Decode: Fallback only if both Memory and Disk caches miss.
 */
object ThumbnailCacheManager {

    private const val TAG = "ThumbnailCacheManager"
    private const val DISK_CACHE_SUBDIR = "thumbnails"
    private val diskScope = CoroutineScope(AppDispatchers.DiskIo)

    // Memory cache budget: 1/8th of available runtime memory capped between 12MB and 32MB
    private val maxMemoryBytes = Runtime.getRuntime().maxMemory().toInt()
    private val cacheSizeBytes = (maxMemoryBytes / 8).coerceIn(1024 * 1024 * 12, 1024 * 1024 * 32)

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSizeBytes) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount
        }

        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            super.entryRemoved(evicted, key, oldValue, newValue)
            // When an entry is evicted from LruCache, route its memory buffer to BitmapPool
            // if it is still valid and mutable, so subsequent thumbnail requests can reuse it.
            if (evicted && oldValue != null && !oldValue.isRecycled && oldValue.isMutable) {
                BitmapPool.release(oldValue)
            }
        }
    }

    private fun getDiskCacheDir(context: Context): File {
        val dir = File(context.cacheDir, DISK_CACHE_SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun sanitizeKey(key: String): String {
        return key.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
    }

    /**
     * Instant, non-blocking lookup in Memory Cache (LruCache).
     * 100% safe for the UI / Main thread.
     */
    fun getMemoryThumbnail(key: String): Bitmap? {
        val safeKey = sanitizeKey(key)
        val memBmp = memoryCache.get(safeKey)
        if (memBmp != null && !memBmp.isRecycled) {
            return memBmp
        }
        return null
    }

    /**
     * Instant, non-blocking check if an entire frame sequence is already present in Memory Cache.
     */
    fun getMemoryFrameSequence(
        videoId: String,
        count: Int,
        width: Int = 120,
        height: Int = 90
    ): List<Bitmap>? {
        val safeId = sanitizeKey(videoId)
        val frames = mutableListOf<Bitmap>()

        for (i in 0 until count) {
            val key = "${safeId}_idx${i}_${width}x${height}"
            val bmp = getMemoryThumbnail(key)
            if (bmp != null && !bmp.isRecycled) {
                frames.add(bmp)
            } else {
                return null
            }
        }

        return if (frames.size == count) frames else null
    }

    /**
     * Asynchronous, non-blocking thumbnail lookup using Two-Tier cache (Memory -> Disk).
     * Executes disk decodes safely on AppDispatchers.DiskIo.
     */
    suspend fun getThumbnailAsync(context: Context, key: String): Bitmap? {
        // Fast path: Memory Cache
        val memBmp = getMemoryThumbnail(key)
        if (memBmp != null) return memBmp

        // Background path: Disk Cache decode
        return kotlinx.coroutines.withContext(AppDispatchers.DiskIo) {
            getThumbnail(context, key)
        }
    }

    /**
     * Asynchronous, non-blocking sequence lookup using Two-Tier cache.
     */
    suspend fun getFrameSequenceAsync(
        context: Context,
        videoId: String,
        count: Int,
        width: Int = 120,
        height: Int = 90
    ): List<Bitmap>? {
        val memSeq = getMemoryFrameSequence(videoId, count, width, height)
        if (memSeq != null) return memSeq

        return kotlinx.coroutines.withContext(AppDispatchers.DiskIo) {
            getFrameSequence(context, videoId, count, width, height)
        }
    }

    /**
     * Retrieves a single thumbnail bitmap from Memory Cache, or Disk Cache if available.
     * Disk decodes utilize reusable Bitmap buffers from BitmapPool via inBitmap.
     */
    fun getThumbnail(context: Context, key: String): Bitmap? {
        val safeKey = sanitizeKey(key)

        // 1. Memory Cache lookup (Fastest, zero decode)
        val memBmp = memoryCache.get(safeKey)
        if (memBmp != null && !memBmp.isRecycled) {
            return memBmp
        }

        // 2. Disk Cache lookup with inBitmap reuse from BitmapPool
        try {
            val diskFile = File(getDiskCacheDir(context), "$safeKey.webp")
            if (diskFile.exists() && diskFile.length() > 0L) {
                val candidate = BitmapPool.acquire(
                    BitmapPool.THUMBNAIL_WIDTH,
                    BitmapPool.THUMBNAIL_HEIGHT,
                    BitmapPool.DEFAULT_CONFIG
                )
                val options = BitmapFactory.Options().apply {
                    inMutable = true
                    if (candidate != null) {
                        inBitmap = candidate
                    }
                }

                val diskBmp = try {
                    BitmapFactory.decodeFile(diskFile.absolutePath, options)
                } catch (e: IllegalArgumentException) {
                    // inBitmap incompatibility fallback: return candidate to pool and decode cleanly
                    BitmapPool.release(candidate)
                    val fallbackOptions = BitmapFactory.Options().apply { inMutable = true }
                    BitmapFactory.decodeFile(diskFile.absolutePath, fallbackOptions)
                } catch (e: Exception) {
                    BitmapPool.release(candidate)
                    null
                }

                if (diskBmp != null) {
                    memoryCache.put(safeKey, diskBmp)
                    return diskBmp
                } else if (candidate != null) {
                    BitmapPool.release(candidate)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read thumbnail from disk cache: ${e.message}")
        }

        return null
    }

    /**
     * Stores a single thumbnail bitmap in Memory Cache and saves to Disk Cache asynchronously.
     */
    fun putThumbnail(context: Context, key: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val safeKey = sanitizeKey(key)

        memoryCache.put(safeKey, bitmap)

        diskScope.launch {
            try {
                val diskFile = File(getDiskCacheDir(context), "$safeKey.webp")
                FileOutputStream(diskFile).use { out ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
                    } else {
                        @Suppress("DEPRECATION")
                        bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write thumbnail to disk cache: ${e.message}")
            }
        }
    }

    /**
     * Retrieves an entire sequence of thumbnails (e.g. 15 frames) for a segment if all frames are cached.
     */
    fun getFrameSequence(
        context: Context,
        videoId: String,
        count: Int,
        width: Int = 120,
        height: Int = 90
    ): List<Bitmap>? {
        val safeId = sanitizeKey(videoId)
        val frames = mutableListOf<Bitmap>()

        for (i in 0 until count) {
            val key = "${safeId}_idx${i}_${width}x${height}"
            val bmp = getThumbnail(context, key)
            if (bmp != null && !bmp.isRecycled) {
                frames.add(bmp)
            } else {
                return null // Incomplete sequence, needs full or partial extraction
            }
        }

        return if (frames.size == count) frames else null
    }

    /**
     * Saves an entire sequence of thumbnails to two-tier cache.
     */
    fun putFrameSequence(
        context: Context,
        videoId: String,
        frames: List<Bitmap>,
        width: Int = 120,
        height: Int = 90
    ) {
        val safeId = sanitizeKey(videoId)
        frames.forEachIndexed { i, bmp ->
            val key = "${safeId}_idx${i}_${width}x${height}"
            putThumbnail(context, key, bmp)
        }
    }

    /**
     * Evicts all in-memory and on-disk thumbnail entries for a specific media URI.
     * Called when a clip is deleted or replaced in the project.
     */
    fun evictForUri(context: Context?, uriKey: String) {
        val safeId = sanitizeKey(uriKey)
        // Evict single thumbnail key
        val singleBmp = memoryCache.remove(safeId)
        if (singleBmp != null && !singleBmp.isRecycled && singleBmp.isMutable) {
            BitmapPool.release(singleBmp)
        }
        // Evict slice keys (idx 0 to 30)
        for (i in 0..30) {
            val key = "${safeId}_idx${i}_120x90"
            val sliceBmp = memoryCache.remove(key)
            if (sliceBmp != null && !sliceBmp.isRecycled && sliceBmp.isMutable) {
                BitmapPool.release(sliceBmp)
            }
        }

        if (context != null) {
            diskScope.launch {
                try {
                    val dir = getDiskCacheDir(context)
                    if (dir.exists() && dir.isDirectory) {
                        dir.listFiles { file -> file.name.startsWith(safeId) }?.forEach { it.delete() }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to evict disk thumbnails for $safeId: ${e.message}")
                }
            }
        }
    }


    /**
     * Trims in-memory LruCache and BitmapPool upon system memory pressure events.
     */
    fun trimMemory(level: Int) {
        synchronized(memoryCache) {
            when {
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                    memoryCache.trimToSize(cacheSizeBytes / 4)
                    BitmapPool.clear()
                }
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                    memoryCache.trimToSize(cacheSizeBytes / 2)
                    BitmapPool.trim(4L * 1024 * 1024)
                }
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                    BitmapPool.trim(4L * 1024 * 1024)
                }
            }
        }
    }

    /**
     * Clears in-memory LruCache and BitmapPool to free RAM.
     */
    fun clearMemoryCache() {
        synchronized(memoryCache) {
            memoryCache.evictAll()
        }
        BitmapPool.clear()
    }
}
