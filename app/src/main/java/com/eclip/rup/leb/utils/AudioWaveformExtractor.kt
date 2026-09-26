package com.eclip.rup.leb.utils

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.antonkarpenko.ffmpegkit.FFmpegKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

object AudioWaveformExtractor {

    private val waveformCache = LruCache<String, FloatArray>(50)
    private val audioProbeCache = LruCache<String, AudioProbeInfo>(50)
    private val extractionMutex = Mutex()
    private val probeMutex = Mutex()
    private val extractionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inFlightRequests = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<FloatArray?>>()
    private val EMPTY_WAVEFORM = FloatArray(0)

    /**
     * Maximum audio duration to decode for waveform generation (3 minutes).
     * Files exceeding this (e.g. 12-hour podcasts or videos) will have their first 3 minutes
     * decoded in ~400ms (generating ~2.8MB PCM instead of 700MB+), preventing device overheat,
     * flash memory wear, and multi-minute background GC freezing.
     */
    private const val MAX_WAVEFORM_DECODE_DURATION_SEC = 180

    private data class AudioProbeInfo(
        val hasAudio: Boolean,
        val durationMs: Long
    )

    /**
     * Fast check to query if audio presence is already known from cache.
     * Returns true/false if cached, or null if not yet probed.
     */
    fun hasAudioStream(filePath: String): Boolean? {
        val file = File(filePath)
        if (!file.exists()) return null
        val cacheKey = "$filePath:${file.length()}"
        return audioProbeCache.get(cacheKey)?.hasAudio
    }

    /**
     * Fast synchronous check for already-extracted waveform amplitudes in memory cache.
     * Returns null immediately without blocking or launching coroutines if not cached.
     */
    fun getCachedWaveform(filePath: String, targetSamples: Int = 300): FloatArray? {
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) return null
        val cacheKey = "$filePath:${file.lastModified()}:${file.length()}:$targetSamples"
        val cached = waveformCache.get(cacheKey)
        return if (cached != null && cached.isNotEmpty()) cached else null
    }

    /**
     * Extracts an audio waveform from a video or audio file using FFmpeg with streaming chunked I/O.
     * Memory usage is constant O(1) (~8KB buffer) regardless of file size, preventing OutOfMemoryError.
     * Concurrent requests for the same media file are deduplicated in-flight.
     *
     * @param videoPath The absolute path to the media file.
     * @param targetSamples The number of data points (amplitudes) to generate (e.g. 300 for a detailed wave).
     * @param knownDurationMs Optional pre-extracted duration in ms to bypass redundant retriever probing.
     * @return A FloatArray of normalized amplitudes (0.0 to 1.0), or null if extraction failed.
     */
    suspend fun extractWaveform(
        context: Context,
        videoPath: String,
        targetSamples: Int = 300,
        knownDurationMs: Long = 0L
    ): FloatArray? = withContext(Dispatchers.IO) {
        val file = File(videoPath)
        if (!file.exists() || file.length() == 0L) return@withContext null

        val cacheKey = "$videoPath:${file.lastModified()}:${file.length()}:$targetSamples"
        waveformCache.get(cacheKey)?.let { cached ->
            return@withContext if (cached.isEmpty()) null else cached
        }

        // Deduplicate concurrent extractions for the exact same file and sample count
        val deferred = inFlightRequests.computeIfAbsent(cacheKey) {
            extractionScope.async {
                try {
                    doExtractWaveform(context, videoPath, targetSamples, knownDurationMs, file, cacheKey)
                } finally {
                    inFlightRequests.remove(cacheKey)
                }
            }
        }
        deferred.await()
    }

    private suspend fun doExtractWaveform(
        context: Context,
        videoPath: String,
        targetSamples: Int,
        knownDurationMs: Long,
        file: File,
        cacheKey: String
    ): FloatArray? {
        // Fast-path: Check cache again inside worker
        waveformCache.get(cacheKey)?.let { cached ->
            return if (cached.isEmpty()) null else cached
        }

        // Probe: Check if the file contains an audio stream and probe duration before spawning FFmpeg.
        // This avoids wasting CPU/memory on silent videos or screen recordings.
        val probe = probeAudioInfo(videoPath, file)
        if (!probe.hasAudio) {
            Log.d("AudioWaveformExtractor", "No audio stream found in $videoPath, skipping waveform extraction")
            waveformCache.put(cacheKey, EMPTY_WAVEFORM)
            return null
        }

        val effectiveDurationMs = if (knownDurationMs > 0L) knownDurationMs else probe.durationMs
        val pcmFile = File(context.cacheDir, "temp_waveform_${java.util.UUID.randomUUID()}.pcm")

        try {
            // Re-check cache before acquiring lock
            waveformCache.get(cacheKey)?.let { cached ->
                if (cached.isNotEmpty()) return cached
            }

            val durationArg = if (effectiveDurationMs > MAX_WAVEFORM_DECODE_DURATION_SEC * 1000L || effectiveDurationMs <= 0L) {
                "-t $MAX_WAVEFORM_DECODE_DURATION_SEC"
            } else {
                ""
            }
            val command = "-y -i \"$videoPath\" -vn -ac 1 -ar 8000 $durationArg -f s16le \"${pcmFile.absolutePath}\""

            val session = extractionMutex.withLock {
                // Double-checked locking pattern: Check if a preceding sibling coroutine finished
                waveformCache.get(cacheKey)?.let { cached ->
                    if (cached.isNotEmpty()) return cached
                }
                FFmpegKit.execute(command)
            }

            if (!session.returnCode.isValueSuccess) {
                Log.e("AudioWaveformExtractor", "FFmpeg failed to extract PCM: ${session.failStackTrace}")
                return null
            }

            val pcmLength = pcmFile.length()
            if (!pcmFile.exists() || pcmLength == 0L) {
                return null
            }

            val totalSamples = (pcmLength / 2).toInt() // 2 bytes per 16-bit sample
            if (totalSamples <= 0) return null

            val result = FloatArray(targetSamples)
            val samplesPerBucket = Math.max(1.0, totalSamples.toDouble() / targetSamples.toDouble())

            var maxGlobalAmp = 0f

            // Stream PCM data using a small 8KB buffer (4096 samples) to prevent OutOfMemoryError on large files
            val bufferBytes = ByteArray(8192)
            val byteBuffer = ByteBuffer.wrap(bufferBytes).order(ByteOrder.LITTLE_ENDIAN)

            FileInputStream(pcmFile).use { fis ->
                var currentSampleGlobalIndex = 0
                var bytesRead = 0

                while (kotlinx.coroutines.currentCoroutineContext().let { it[kotlinx.coroutines.Job]?.isActive != false } && fis.read(bufferBytes).also { bytesRead = it } != -1) {
                    val samplesInChunk = bytesRead / 2
                    byteBuffer.rewind()
                    byteBuffer.limit(bytesRead)

                    for (s in 0 until samplesInChunk) {
                        val sample = Math.abs(byteBuffer.short.toFloat())
                        val sampleIdx = currentSampleGlobalIndex + s
                        val bucketIdx = Math.min((sampleIdx / samplesPerBucket).toInt(), targetSamples - 1)

                        if (sample > result[bucketIdx]) {
                            result[bucketIdx] = sample
                        }
                        if (sample > maxGlobalAmp) {
                            maxGlobalAmp = sample
                        }
                    }
                    currentSampleGlobalIndex += samplesInChunk
                }
            }

            // Normalize all values between 0.0 and 1.0 based on the highest peak
            if (maxGlobalAmp > 0f) {
                for (i in 0 until targetSamples) {
                    result[i] = result[i] / maxGlobalAmp
                }
            }

            waveformCache.put(cacheKey, result)

            return result

        } catch (t: Throwable) {
            Log.e("AudioWaveformExtractor", "Error processing waveform", t)
            return null
        } finally {
            try {
                if (pcmFile.exists()) {
                    pcmFile.delete()
                }
            } catch (_: Exception) {}
        }
    }

    fun evictForVideoPath(videoPath: String) {
        val snapshot = waveformCache.snapshot()
        for (key in snapshot.keys) {
            if (key.startsWith(videoPath)) {
                waveformCache.remove(key)
            }
        }
    }

    fun clearCache() {
        waveformCache.evictAll()
        audioProbeCache.evictAll()
        inFlightRequests.clear()
    }

    /**
     * Lightweight probe to check if a media file contains an audio stream and probe its duration.
     * Uses MediaMetadataRetriever once and caches the result by path and file size.
     * Serialized with probeMutex to prevent parallel MediaMetadataRetriever allocations on identical media.
     */
    private suspend fun probeAudioInfo(filePath: String, file: File): AudioProbeInfo {
        val cacheKey = "$filePath:${file.length()}"
        audioProbeCache.get(cacheKey)?.let { return it }

        return probeMutex.withLock {
            audioProbeCache.get(cacheKey)?.let { return it }

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(filePath)
                val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val info = AudioProbeInfo(hasAudio, durationMs)
                audioProbeCache.put(cacheKey, info)
                info
            } catch (e: Exception) {
                Log.w("AudioWaveformExtractor", "Audio probe failed for $filePath: ${e.message}")
                val info = AudioProbeInfo(hasAudio = false, durationMs = 0L)
                audioProbeCache.put(cacheKey, info)
                info
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }
}
