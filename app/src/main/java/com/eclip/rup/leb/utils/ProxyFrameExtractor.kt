package com.eclip.rup.leb.utils

import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * ProxyFrameExtractor is responsible for decoding proxy frames (480p, ~15fps)
 * from the source video clip using hardware codec permits.
 * Extracts frames for a specific time window and caches them in RAMFrameBufferManager.
 */
object ProxyFrameExtractor {
    private const val TAG = "ProxyFrameExtractor"
    private const val TARGET_FPS = 15
    private const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS

    /**
     * Extracts frames for a given time window and stores them in RAM cache.
     * Starts from [startMs] up to [endMs] with a step of [FRAME_INTERVAL_MS].
     */
    suspend fun extractFramesForWindow(
        clipId: String,
        sourceFilePath: String,
        startMs: Long,
        endMs: Long
    ) = withContext(Dispatchers.IO) {
        // Enforce maximum width/height for the proxy frame
        val targetWidth = RAMFrameBufferManager.PROXY_WIDTH
        val targetHeight = RAMFrameBufferManager.PROXY_HEIGHT

        // Request a hardware codec permit to ensure we don't exhaust decoder instances
        CodecInstancePool.withHardwareCodecPermit {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(sourceFilePath)

                var currentMs = max(0L, startMs)
                while (currentMs <= endMs) {
                    // Check if the frame already exists to avoid redundant decoding
                    if (RAMFrameBufferManager.getFrameNear(clipId, currentMs, 30) == null) {
                        val timeUs = currentMs * 1000L
                        
                        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            // Fast, scaled decoding natively on Android 8.1+
                            retriever.getScaledFrameAtTime(
                                timeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                targetWidth,
                                targetHeight
                            )
                        } else {
                            // Fallback
                            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { original ->
                                val scaled = android.graphics.Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true)
                                if (scaled != original) {
                                    original.recycle()
                                }
                                scaled
                            }
                        }

                        if (bitmap != null) {
                            // Since BitmapPool might return an ARGB_8888 mutable bitmap,
                            // we could ideally copy into it. For simplicity, we just put the decoded bitmap.
                            // In a robust scenario, we use BitmapFactory options, but MediaMetadataRetriever
                            // creates its own bitmap.
                            RAMFrameBufferManager.putFrame(clipId, currentMs, bitmap)
                        } else {
                            Log.w(TAG, "Failed to decode proxy frame at $currentMs ms for clip $clipId")
                        }
                    }
                    currentMs += FRAME_INTERVAL_MS
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting proxy frames: ${e.message}", e)
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    // Ignore release errors
                }
            }
        }
    }
}
