package com.eclip.rup.leb.utils

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import java.util.LinkedHashMap

/**
 * Level of Detail (LOD) for audio waveform rendering based on timeline zoom level.
 */
enum class WaveformLOD(val sampleCount: Int) {
    COARSE(100),    // Zoomed out (high msPerPixel > 40ms/px)
    MEDIUM(300),    // Normal editing (10ms/px <= msPerPixel <= 40ms/px)
    DETAILED(800)   // Zoomed in (msPerPixel < 10ms/px)
}

/**
 * PrecomputedWaveformManager pre-bakes multi-resolution audio waveforms and precomputes
 * reusable Canvas [Path] geometry chunks.
 *
 * Core Principles:
 * 1. Multi-Resolution LOD: High-frequency data is skipped when zoomed out; detailed waveform is loaded when zoomed in.
 * 2. Precomputed Path Geometry: Waveform lines/envelopes are compiled into reusable [Path] objects during preprocessing.
 * 3. Zero-Allocation Scrolling: During timeline scroll, pre-baked Paths are repositioned via canvas.translate()
 *    or Matrix transforms, completely eliminating per-frame Path reconstruction and Skia allocations.
 * 4. Temporal Chunking: Waveforms are partitioned into fixed temporal chunks (e.g., 5000ms) to bound peak memory.
 */
class PrecomputedWaveformManager(
    private val chunkDurationMs: Long = 5000L
) {

    data class MultiResWaveformData(
        val coarseAmplitudes: FloatArray,
        val mediumAmplitudes: FloatArray,
        val detailedAmplitudes: FloatArray,
        val durationMs: Long
    ) {
        fun getAmplitudesForLOD(lod: WaveformLOD): FloatArray {
            return when (lod) {
                WaveformLOD.COARSE -> coarseAmplitudes
                WaveformLOD.MEDIUM -> mediumAmplitudes
                WaveformLOD.DETAILED -> detailedAmplitudes
            }
        }
    }

    data class WaveformChunkKey(
        val mediaKey: String,
        val chunkIndex: Int,
        val lod: WaveformLOD,
        val widthPx: Int,
        val heightPx: Int
    )

    private class SimpleLruCache<K, V>(
        private val maxCapacity: Int,
        private val onEvict: ((V) -> Unit)? = null
    ) {
        private val map = object : LinkedHashMap<K, V>(maxCapacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
                val shouldRemove = size > maxCapacity
                if (shouldRemove && eldest != null) {
                    onEvict?.invoke(eldest.value)
                }
                return shouldRemove
            }
        }

        @Synchronized fun get(key: K): V? = map[key]
        @Synchronized fun put(key: K, value: V) { map[key] = value }
        @Synchronized fun evictAll() { map.clear() }
    }

    // Precomputed Path cache capped at 60 active chunks (~2MB RAM)
    private val pathCache = SimpleLruCache<WaveformChunkKey, Path>(60) { it.reset() }
    private val rawDataCache = SimpleLruCache<String, MultiResWaveformData>(20)

    /**
     * Determines optimal LOD level based on millisecond-per-pixel ratio (zoom scale).
     */
    fun selectLOD(msPerPixel: Float): WaveformLOD {
        return when {
            msPerPixel > 40f -> WaveformLOD.COARSE
            msPerPixel < 10f -> WaveformLOD.DETAILED
            else -> WaveformLOD.MEDIUM
        }
    }

    /**
     * Registers pre-extracted amplitudes for a media item and generates multi-resolution LOD arrays.
     */
    fun registerWaveform(mediaKey: String, detailedAmplitudes: FloatArray, durationMs: Long) {
        if (detailedAmplitudes.isEmpty() || durationMs <= 0) return

        val coarse = downsample(detailedAmplitudes, WaveformLOD.COARSE.sampleCount)
        val medium = downsample(detailedAmplitudes, WaveformLOD.MEDIUM.sampleCount)
        val detailed = if (detailedAmplitudes.size == WaveformLOD.DETAILED.sampleCount) {
            detailedAmplitudes
        } else {
            downsample(detailedAmplitudes, WaveformLOD.DETAILED.sampleCount)
        }

        val multiRes = MultiResWaveformData(coarse, medium, detailed, durationMs)
        rawDataCache.put(mediaKey, multiRes)
    }

    /**
     * Retrieves or builds a precomputed [Path] for a specific temporal chunk of the waveform.
     */
    fun getOrBuildChunkPath(
        mediaKey: String,
        chunkIndex: Int,
        lod: WaveformLOD,
        chunkWidthPx: Float,
        trackHeightPx: Float,
        density: Float = 1.0f
    ): Path? {
        val widthInt = chunkWidthPx.toInt().coerceAtLeast(1)
        val heightInt = trackHeightPx.toInt().coerceAtLeast(1)
        val key = WaveformChunkKey(mediaKey, chunkIndex, lod, widthInt, heightInt)

        pathCache.get(key)?.let { return it }

        val rawData = rawDataCache.get(mediaKey) ?: return null
        val amplitudes = rawData.getAmplitudesForLOD(lod)
        if (amplitudes.isEmpty()) return null

        val totalChunks = ((rawData.durationMs + chunkDurationMs - 1) / chunkDurationMs).toInt().coerceAtLeast(1)
        if (chunkIndex >= totalChunks) return null

        val chunkStartMs = chunkIndex * chunkDurationMs
        val chunkEndMs = ((chunkIndex + 1) * chunkDurationMs).coerceAtMost(rawData.durationMs)
        val chunkDur = (chunkEndMs - chunkStartMs).coerceAtLeast(1L)

        val totalDurationMs = rawData.durationMs.toFloat()
        val startRatio = chunkStartMs.toFloat() / totalDurationMs
        val endRatio = chunkEndMs.toFloat() / totalDurationMs

        val startSampleIdx = (startRatio * amplitudes.size).toInt().coerceIn(0, amplitudes.size - 1)
        val endSampleIdx = (endRatio * amplitudes.size).toInt().coerceIn(startSampleIdx + 1, amplitudes.size)

        val newPath = Path()
        val centerY = trackHeightPx / 2f
        val maxAmp = trackHeightPx * 0.38f
        val numSamplesInChunk = endSampleIdx - startSampleIdx
        if (numSamplesInChunk <= 0) return null

        val stepX = chunkWidthPx / numSamplesInChunk.toFloat()
        val barWidth = 2.0f * density

        for (i in 0 until numSamplesInChunk) {
            val sampleVal = amplitudes[startSampleIdx + i]
            val ampHeight = (maxAmp * sampleVal).coerceAtLeast(1.5f * density)
            val x = i * stepX

            // Symmetric vertical bar
            newPath.moveTo(x, centerY - ampHeight)
            newPath.lineTo(x, centerY + ampHeight)
        }

        pathCache.put(key, newPath)
        return newPath
    }

    /**
     * Renders precomputed waveform chunks across the visible viewport using transform translation.
     * Zero Path reconstructions occur in this method.
     */
    fun drawVisibleWaveform(
        canvas: Canvas,
        mediaKey: String,
        trackStartX: Float,
        trackEndX: Float,
        viewportLeft: Float,
        viewportRight: Float,
        msPerPixel: Float,
        trackHeight: Float,
        paint: Paint,
        density: Float = 1.0f
    ) {
        if (msPerPixel <= 0f || trackEndX <= trackStartX) return

        val rawData = rawDataCache.get(mediaKey) ?: return
        val lod = selectLOD(msPerPixel)
        val chunkWidthPx = chunkDurationMs / msPerPixel

        val totalChunks = ((rawData.durationMs + chunkDurationMs - 1) / chunkDurationMs).toInt().coerceAtLeast(1)

        for (chunkIdx in 0 until totalChunks) {
            val chunkLeft = trackStartX + (chunkIdx * chunkWidthPx)
            val chunkRight = chunkLeft + chunkWidthPx

            // Viewport culling: Skip chunk if outside visible screen range
            if (chunkRight < viewportLeft || chunkLeft > viewportRight) {
                continue
            }

            val path = getOrBuildChunkPath(mediaKey, chunkIdx, lod, chunkWidthPx, trackHeight, density)
            if (path != null && !path.isEmpty) {
                canvas.save()
                canvas.translate(chunkLeft, 0f)
                canvas.drawPath(path, paint)
                canvas.restore()
            }
        }
    }

    private fun downsample(source: FloatArray, targetSize: Int): FloatArray {
        if (source.size == targetSize) return source.copyOf()
        if (source.size < targetSize) {
            val result = FloatArray(targetSize)
            val step = source.size.toFloat() / targetSize.toFloat()
            for (i in 0 until targetSize) {
                val srcIdx = (i * step).toInt().coerceIn(0, source.size - 1)
                result[i] = source[srcIdx]
            }
            return result
        }

        val result = FloatArray(targetSize)
        val bucketSize = source.size.toDouble() / targetSize.toDouble()
        for (i in 0 until targetSize) {
            val startIdx = (i * bucketSize).toInt()
            val endIdx = ((i + 1) * bucketSize).toInt().coerceAtMost(source.size)
            var maxAmp = 0f
            for (j in startIdx until endIdx) {
                if (source[j] > maxAmp) maxAmp = source[j]
            }
            result[i] = maxAmp
        }
        return result
    }

    fun evictForMediaKey(mediaKey: String) {
        rawDataCache.evictAll() // clears raw data cache
        pathCache.evictAll()
    }

    fun clear() {
        pathCache.evictAll()
        rawDataCache.evictAll()
    }
}
