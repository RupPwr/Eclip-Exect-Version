package com.eclip.rup.leb.utils

import android.content.Context
import android.net.Uri
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.Log
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.antonkarpenko.ffmpegkit.Statistics
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioAnalyzer {
    
    /**
     * Extracts PCM audio from the given Uri using FFmpeg and runs a simple energy-based
     * onset (beat) detection algorithm.
     * Returns a list of timestamps (in milliseconds) where beats were detected.
     *
     * Threading Contract:
     * - Execution runs asynchronously in the background.
     * - [onProgress] and [onComplete] callbacks are invoked on a background worker thread.
     *   Callers needing UI updates must dispatch to the Main thread (e.g., via lifecycleScope).
     */
    fun detectBeats(
        context: Context,
        audioUri: Uri,
        onProgress: (Int) -> Unit = {},
        onComplete: (List<Long>) -> Unit
    ) {
        val cacheDir = context.cacheDir
        val pcmFile = File(cacheDir, "temp_beat_detect_${System.currentTimeMillis()}.raw")
        
        // We use 8000Hz mono 16-bit to keep the file small and processing fast.
        val inputPath = try {
            if (audioUri.scheme == "content") {
                FFmpegKitConfig.getSafParameterForRead(context, audioUri)
            } else {
                audioUri.path ?: audioUri.toString()
            }
        } catch (e: Exception) {
            audioUri.path ?: audioUri.toString()
        }
        val cmd = "-y -i \"$inputPath\" -f s16le -ac 1 -ar 8000 \"${pcmFile.absolutePath}\""
        
        try {
            FFmpegKit.executeAsync(cmd, { session: FFmpegSession ->
                try {
                    if (ReturnCode.isSuccess(session.returnCode)) {
                        val beats = analyzePcmForBeats(pcmFile)
                        onComplete(beats)
                    } else {
                        onComplete(emptyList())
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("AudioAnalyzer", "Error processing beats", t)
                    onComplete(emptyList())
                } finally {
                    if (pcmFile.exists()) pcmFile.delete()
                }
            }, { log: Log ->
                // Could parse progress if we wanted
            }, { statistics: Statistics ->
                // Could parse time for progress
            })
        } catch (e: Exception) {
            if (pcmFile.exists()) pcmFile.delete()
            onComplete(emptyList())
        }
    }

    private fun analyzePcmForBeats(pcmFile: File): List<Long> {
        if (!pcmFile.exists() || pcmFile.length() == 0L) return emptyList()

        val sampleRate = 8000
        val windowDurationMs = 20
        val samplesPerWindow = (sampleRate * windowDurationMs) / 1000 // 160 samples
        val bytesPerWindow = samplesPerWindow * 2 // 320 bytes
        
        val totalSamples = (pcmFile.length() / 2).toInt()
        val numWindows = totalSamples / samplesPerWindow
        if (numWindows <= 0) return emptyList()
        
        val energies = FloatArray(numWindows)
        
        try {
            val windowBuffer = ByteArray(bytesPerWindow)
            val byteBuffer = ByteBuffer.wrap(windowBuffer).order(ByteOrder.LITTLE_ENDIAN)
            
            FileInputStream(pcmFile).use { fis ->
                var windowIdx = 0
                while (windowIdx < numWindows) {
                    var bytesReadTotal = 0
                    while (bytesReadTotal < bytesPerWindow) {
                        val read = fis.read(windowBuffer, bytesReadTotal, bytesPerWindow - bytesReadTotal)
                        if (read == -1) break
                        bytesReadTotal += read
                    }
                    if (bytesReadTotal < bytesPerWindow) break
                    
                    byteBuffer.rewind()
                    var sum = 0.0
                    for (j in 0 until samplesPerWindow) {
                        val sample = byteBuffer.short.toDouble()
                        sum += sample * sample
                    }
                    energies[windowIdx] = Math.sqrt(sum / samplesPerWindow).toFloat()
                    windowIdx++
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("AudioAnalyzer", "Error analyzing PCM for beats", t)
            return emptyList()
        }
        
        // Simple peak picking with moving average
        val beats = mutableListOf<Long>()
        val historySize = 50 // 1 second of history (50 * 20ms)
        
        // Thresholds
        val minEnergyThreshold = 1000f // Ignore quiet parts completely
        val multiplier = 1.4f // Must be 40% louder than local average
        
        for (i in 0 until numWindows) {
            val currentEnergy = energies[i]
            if (currentEnergy < minEnergyThreshold) continue
            
            // Calculate local moving average
            val startIdx = maxOf(0, i - historySize)
            val endIdx = minOf(numWindows - 1, i + historySize)
            var localSum = 0f
            var count = 0
            for (j in startIdx..endIdx) {
                localSum += energies[j]
                count++
            }
            val localAverage = localSum / count
            
            if (currentEnergy > localAverage * multiplier) {
                // Check if it's a local maximum (peak)
                var isPeak = true
                val checkRange = 3 // 60ms around
                for (j in maxOf(0, i - checkRange)..minOf(numWindows - 1, i + checkRange)) {
                    if (energies[j] > currentEnergy) {
                        isPeak = false
                        break
                    }
                }
                
                if (isPeak) {
                    val timeMs = (i * windowDurationMs).toLong()
                    // Debounce: ensure at least 200ms between beats
                    if (beats.isEmpty() || timeMs - beats.last() > 200) {
                        beats.add(timeMs)
                    }
                }
            }
        }
        
        return beats
    }
}
