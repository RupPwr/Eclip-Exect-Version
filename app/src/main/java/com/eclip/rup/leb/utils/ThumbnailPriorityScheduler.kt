package com.eclip.rup.leb.utils

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue

/**
 * Thumbnail request priorities based on spatial distance to the active viewport.
 */
enum class ThumbnailPriority(val value: Int) {
    VISIBLE(1),      // Visible in current on-screen viewport
    PREFETCH(2),     // In the ±2 clips prefetch cushion
    BACKGROUND(3),   // Pending/background jobs
    OBSOLETE(4)      // Cancelled / obsolete
}

data class ThumbnailTask(
    val key: String,
    val videoId: String,
    val frameIndex: Int,
    val priority: ThumbnailPriority,
    val requestTimeMs: Long = System.currentTimeMillis(),
    val generation: Long = 0L,
    val onLoaded: (Bitmap) -> Unit
) : Comparable<ThumbnailTask> {
    override fun compareTo(other: ThumbnailTask): Int {
        // Lower numeric value = higher priority
        val pDiff = this.priority.value.compareTo(other.priority.value)
        if (pDiff != 0) return pDiff
        
        // Prioritize newer generation (prevent stale execution)
        val genDiff = other.generation.compareTo(this.generation)
        if (genDiff != 0) return genDiff
        
        return this.requestTimeMs.compareTo(other.requestTimeMs)
    }
}

/**
 * ThumbnailPriorityScheduler manages asynchronous thumbnail extraction and decoding
 * based on spatial viewport priority, motion state, and generation tokens.
 *
 * Core Principles:
 * 1. Priority-Based Scheduling: Visible thumbnails decode first; off-screen requests are pruned.
 * 2. Generation-Based Invalidation: Stale requests from previous scroll gestures are discarded.
 * 3. Decoupled Bitmap Lifecycles: Bitmaps are owned by LruCache/BitmapPool, never tied to View recycling.
 * 4. Motion-Aware Throttling: Decoding pauses or yields during DRAGGING/SETTLING and resumes on IDLE.
 * 5. Zero Allocation Hot Path: Reuses BitmapPool buffers via BitmapFactory.Options.inBitmap.
 */
class ThumbnailPriorityScheduler(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(AppDispatchers.Thumbnail)
) {

    private val pendingTasks = PriorityBlockingQueue<ThumbnailTask>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private var isPaused: Boolean = false
    var currentGeneration: Long = 0L

    /**
     * Submits a prioritized thumbnail request with generation token.
     * If already present in Memory Cache, invokes [onLoaded] immediately on caller thread.
     */
    fun requestThumbnail(
        key: String,
        videoId: String,
        frameIndex: Int,
        priority: ThumbnailPriority,
        generation: Long = currentGeneration,
        onLoaded: (Bitmap) -> Unit
    ) {
        TimelineLifecycleLogger.logThumbnailRequest(key, generation, priority)

        // Fast path: Immediate memory cache hit (0ms CPU decode)
        val memBmp = ThumbnailCacheManager.getMemoryThumbnail(key)
        if (memBmp != null && !memBmp.isRecycled) {
            onLoaded(memBmp)
            return
        }

        val task = ThumbnailTask(
            key = key,
            videoId = videoId,
            frameIndex = frameIndex,
            priority = priority,
            generation = generation,
            onLoaded = onLoaded
        )
        pendingTasks.offer(task)
        processNext()
    }

    /**
     * Updates priorities of pending thumbnail requests based on current viewport coordinates.
     * Prunes and cancels requests that have moved completely off-screen or belong to obsolete generations.
     */
    fun updateViewport(
        visibleClipKeys: Set<String>,
        cushionClipKeys: Set<String>,
        generation: Long = currentGeneration
    ) {
        currentGeneration = generation
        val remainingTasks = mutableListOf<ThumbnailTask>()
        val drained = mutableListOf<ThumbnailTask>()
        pendingTasks.drainTo(drained)

        for (task in drained) {
            val isVisible = visibleClipKeys.contains(task.key) || visibleClipKeys.contains(task.videoId)
            val isCushion = cushionClipKeys.contains(task.key) || cushionClipKeys.contains(task.videoId)

            val isThrottled = ThermalProtectionManager.isThrottled

            val updatedPriority = when {
                isVisible -> ThumbnailPriority.VISIBLE
                isCushion -> ThumbnailPriority.PREFETCH
                task.generation == currentGeneration && !isThrottled -> ThumbnailPriority.BACKGROUND
                else -> ThumbnailPriority.OBSOLETE
            }

            if (updatedPriority != ThumbnailPriority.OBSOLETE) {
                remainingTasks.add(task.copy(priority = updatedPriority))
            } else {
                // Cancel active background job if obsolete
                activeJobs.remove(task.key)?.cancel()
            }
        }

        pendingTasks.addAll(remainingTasks)
        processNext()
    }

    /**
     * Pauses decoding workers during high-velocity DRAGGING or SETTLING interactions.
     */
    fun pause() {
        isPaused = true
    }

    /**
     * Resumes decoding workers when timeline enters IDLE state.
     */
    fun resume() {
        isPaused = false
        processNext()
    }

    /**
     * Cancels any pending or active thumbnail jobs associated with a deleted or replaced media item.
     * Alias for backwards compatibility.
     */
    fun cancelTasksForMedia(videoId: String) {
        cancelPendingJobsFor(videoId)
    }

    /**
     * Cancels any pending or active thumbnail jobs associated with a deleted or replaced media item.
     * Prevents accidental execution of cancelled jobs via cooperative cancellation.
     */
    fun cancelPendingJobsFor(clipId: String) {
        val safeId = clipId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val iterator = pendingTasks.iterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            if (task.videoId == clipId || task.key.startsWith(safeId) || task.videoId.startsWith(safeId)) {
                iterator.remove()
            }
        }
        activeJobs.forEach { (key, job) ->
            if (key.startsWith(safeId)) {
                job.cancel() // Cooperative cancellation safely yields resources
                activeJobs.remove(key)
            }
        }
    }

    private fun processNext() {
        if (isPaused) return

        val maxWorkers = ThermalProtectionManager.getRecommendedWorkerConcurrency()
        if (maxWorkers <= 0) return // Throttled due to high thermal load

        while (activeJobs.size < maxWorkers && pendingTasks.isNotEmpty()) {
            val task = pendingTasks.poll() ?: break
            if (task.priority == ThumbnailPriority.OBSOLETE) continue

            // If already in memory cache by now
            val cachedBmp = ThumbnailCacheManager.getMemoryThumbnail(task.key)
            if (cachedBmp != null && !cachedBmp.isRecycled) {
                kotlinx.coroutines.MainScope().launch {
                    task.onLoaded(cachedBmp)
                }
                continue
            }

            val job = scope.launch {
                try {
                    val bmp = ThumbnailCacheManager.getThumbnailAsync(context, task.key)
                    if (bmp != null && !bmp.isRecycled) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            task.onLoaded(bmp)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    activeJobs.remove(task.key)
                    processNext()
                }
            }
            activeJobs[task.key] = job
        }
    }

    /**
     * Cancels all active and pending thumbnail requests.
     */
    fun clear() {
        pendingTasks.clear()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        scope.coroutineContext.cancelChildren()
    }
}
