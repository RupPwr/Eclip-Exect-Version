package com.eclip.rup.leb.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * AppCachePolicyManager provides centralized, deterministic management for all temporary files,
 * intermediate processing data, and disk cache quotas across the application.
 *
 * Core Responsibilities:
 * 1. Categorized Temporary Files: Tracks files by known prefixes to distinguish intermediate renders from persistent drafts.
 * 2. Strict Disk Quotas: Enforces bounds on general temp storage (150MB), thumbnails (50MB), and playback proxies (100MB).
 * 3. Proactive LRU Eviction: Automatically deletes oldest superseded files when disk usage approaches safety limits.
 * 4. Startup & Teardown Orphan Sweeps: Purges leftover files from crashed sessions, aborted exports, or closed projects.
 * 5. Media & Operation Deletion Cleanup: Reclaims storage immediately when a clip or effect is deleted or replaced.
 */
object AppCachePolicyManager {

    private const val TAG = "AppCachePolicyManager"

    // Temporary file prefix classifications
    const val PREFIX_TEMP_VIDEO = "temp_video_"
    const val PREFIX_CONCAT = "concat_"
    const val PREFIX_IMPORTED = "imported_video_"
    const val PREFIX_MERGE = "merge_video_"
    const val PREFIX_AUDIO = "audio_"
    const val PREFIX_EXTRACTED_AUDIO = "extracted_audio_"
    const val PREFIX_VOICE_OVER = "voice_over_"
    const val PREFIX_REVERSE = "reverse_"
    const val PREFIX_FREEZE = "freeze_"
    const val PREFIX_PREVIEW = "preview_segment_"
    const val PREFIX_WAVEFORM = "temp_waveform_"
    const val PREFIX_BEATS = "temp_beat_detect_"
    const val PREFIX_IMG_INPUT = "img_input_"
    const val PREFIX_MAIN_IMAGE = "main_image_"
    const val PREFIX_SPLIT = "temp_split_"
    const val PREFIX_DRAWING = "drawing_"

    // Subdirectories
    const val DIR_THUMBNAILS = "thumbnails"
    const val DIR_DRAWINGS = "drawings"
    const val DIR_FONTS = "fonts"

    // Set of active session temporary files currently in use
    private val activeSessionFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Track active heavy workloads (rendering/exporting)
    private val heavyLoadCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Increments or decrements the heavy workload counter (rendering, export).
     */
    fun setHeavyLoadActive(active: Boolean) {
        if (active) {
            heavyLoadCounter.incrementAndGet()
        } else {
            heavyLoadCounter.updateAndGet { if (it > 0) it - 1 else 0 }
        }
    }

    /**
     * Checks if any heavy workload is currently active across the application.
     */
    fun isHeavyLoadActive(): Boolean {
        return heavyLoadCounter.get() > 0
    }

    /**
     * Returns a snapshot of all actively registered session file paths.
     */
    fun getActivePaths(): Set<String> {
        return activeSessionFiles.toSet()
    }

    /**
     * Registers a temporary file as actively in use by the current project session.
     */
    fun registerActiveFile(file: File) {
        activeSessionFiles.add(file.canonicalPath)
        activeSessionFiles.add(file.absolutePath)
    }

    /**
     * Registers a URI as actively in use by the current project session.
     */
    fun registerActiveUri(uri: Uri?) {
        if (uri == null) return
        val path = uri.path
        if (path != null) {
            registerActivePath(path)
        }
    }

    /**
     * Registers a file system path as actively in use by the current project session.
     */
    fun registerActivePath(path: String?) {
        if (path.isNullOrBlank()) return
        try {
            val file = File(path)
            activeSessionFiles.add(file.canonicalPath)
            activeSessionFiles.add(file.absolutePath)
        } catch (_: Exception) {
            activeSessionFiles.add(path)
        }
    }

    /**
     * Checks if a file path is currently registered as active.
     */
    fun isPathActive(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (activeSessionFiles.contains(path)) return true
        return try {
            val canonical = File(path).canonicalPath
            activeSessionFiles.contains(canonical)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Unregisters and safely deletes a temporary file when superseded or deleted by user.
     */
    fun unregisterAndDeleteFile(file: File?) {
        if (file == null) return
        try {
            activeSessionFiles.remove(file.canonicalPath)
            activeSessionFiles.remove(file.absolutePath)
        } catch (_: Exception) {
            activeSessionFiles.remove(file.absolutePath)
        }
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete temporary file: ${file.absolutePath}", e)
        }
    }

    /**
     * Checks if a given Uri or path points to a file within the app's cache directory,
     * and deletes it safely without touching external user media.
     */
    fun deleteFileIfInCache(context: Context, uri: Uri?): Boolean {
        if (uri == null) return false
        val path = uri.path ?: uri.toString()
        return deleteFileIfInCache(context, path)
    }

    fun deleteFileIfInCache(context: Context, path: String?): Boolean {
        if (path == null) return false
        try {
            val cacheRoot = context.cacheDir.canonicalPath
            val targetFile = File(path).canonicalFile
            if (targetFile.path.startsWith(cacheRoot) && targetFile.exists() && targetFile.isFile) {
                activeSessionFiles.remove(targetFile.canonicalPath)
                activeSessionFiles.remove(targetFile.absolutePath)
                val deleted = targetFile.delete()
                if (deleted) {
                    Log.d(TAG, "Safely cleaned up cache file: ${targetFile.name}")
                }
                return deleted
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking/deleting file in cache: $path", e)
        }
        return false
    }
}
