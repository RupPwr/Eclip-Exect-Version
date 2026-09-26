package com.eclip.rup.leb.services

import android.content.Context
import android.net.Uri
import android.util.Log
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.Level
import com.antonkarpenko.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.File

import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * FFmpegRenderEngine handles all FFmpeg operations for the video editor.
 *
 * FONT NOTE:
 * drawtext requires fontfile= pointing to a TTF/OTF file on disk.
 * font= does NOT exist in FFmpeg's drawtext filter — it always causes:
 *   "Error applying option 'font' to filter 'drawtext': Option not found"
 *
 * Usage:
 *   1. Place TTF at app/src/main/assets/fonts/Roboto-Regular.ttf
 *   2. Call copyFontToCache() once in Activity.onCreate
 *   3. Pass the returned path as fontFilePath to all text render methods
 */
class FFmpegRenderEngine(private val context: Context) {

    private val activeSessions = CopyOnWriteArrayList<FFmpegSession>()
    private val sessionLogBuffers = ConcurrentHashMap<Long, ConcurrentLinkedQueue<com.antonkarpenko.ffmpegkit.Log>>()
    private val TAG = "FFmpegRenderEngine"

    init {
        // System fonts as a last-resort fallback — not reliable on all Android devices.
        // Always supply an explicit fontFilePath for guaranteed text rendering.
        // Probe multiple vendor font directories since OEMs differ.
        val fontDir = listOf(
            "/system/fonts",
            "/system/font",
            "/data/fonts",
            "/product/fonts"
        ).firstOrNull { File(it).isDirectory } ?: "/system/fonts"

        try {
            FFmpegKitConfig.setFontDirectory(context, fontDir, null)
            Log.d(TAG, "Font directory set to: $fontDir")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set font directory '$fontDir': ${e.message}")
        }

        // Register a global log callback so every FFmpeg stderr line hits logcat and session buffer.
        try {
            FFmpegKitConfig.enableLogCallback { log ->
                val msg = log.message?.trimEnd() ?: return@enableLogCallback
                if (msg.isEmpty()) return@enableLogCallback

                val sId = log.sessionId
                if (sId != 0L) {
                    val buf = sessionLogBuffers.getOrPut(sId) { ConcurrentLinkedQueue() }
                    if (buf.size < 2000) {
                        buf.add(log)
                    }
                }

                when (log.level) {
                    Level.AV_LOG_ERROR, Level.AV_LOG_FATAL, Level.AV_LOG_PANIC, Level.AV_LOG_STDERR
                        -> Log.e(TAG, "[ffmpeg] $msg")
                    Level.AV_LOG_WARNING
                        -> Log.w(TAG, "[ffmpeg] $msg")
                    else -> Log.v(TAG, "[ffmpeg] $msg")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not register FFmpegKit global log callback: ${e.message}")
        }
    }

    // ── Result type ───────────────────────────────────────────────────────────

    sealed class RenderResult {
        data class Success(val outputPath: String, val session: FFmpegSession) : RenderResult()
        data class Failure(val error: String, val userMessage: String? = null, val session: FFmpegSession? = null) : RenderResult()
        object Cancelled : RenderResult()
    }

    // ── Font helper ───────────────────────────────────────────────────────────

    /**
     * Copies a font from assets to cacheDir so FFmpeg can access it as a plain path.
     *
     * @param assetPath  e.g. "fonts/Roboto-Regular.ttf"
     * @return           Absolute path to the cached file, or null on failure.
     */
    fun copyFontToCache(assetPath: String = "fonts/Roboto-Regular.ttf"): String? {
        return try {
            val fileName = assetPath.substringAfterLast('/')
            val fontFile = File(context.cacheDir, fileName)
            if (!fontFile.exists()) {
                context.assets.open(assetPath).use { input ->
                    fontFile.outputStream().use { input.copyTo(it) }
                }
                Log.d(TAG, "Font copied to cache: ${fontFile.absolutePath}")
            } else {
                Log.d(TAG, "Font already in cache: ${fontFile.absolutePath}")
            }
            
            val fontMap = mutableMapOf<String, String>()
            val alias = fileName.substringBeforeLast('.')
            fontMap[alias] = fileName
            FFmpegKitConfig.setFontDirectory(context, context.cacheDir.absolutePath, fontMap)
            
            fontFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy font to cache: ${e.message}", e)
            null
        }
    }

    // ── Core execution ────────────────────────────────────────────────────────

    /**
     * Execute an FFmpeg command and return the result.
     * Runs on IO dispatcher for non-blocking execution.
     */
    suspend fun executeCommand(command: String, retryLevel: Int = 0): RenderResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Executing FFmpeg command: $command")
                
                val isHardwareCodec = command.contains("mediacodec")
                val sessionBlock: suspend () -> FFmpegSession = {
                    suspendCancellableCoroutine { cont ->
                        val asyncSession = FFmpegKit.executeAsync(command, { completeSession ->
                            cont.resume(completeSession)
                        }, { log ->
                            val msg = log.message?.trimEnd() ?: return@executeAsync
                            if (msg.isNotEmpty()) {
                                val sId = log.sessionId
                                if (sId != 0L) {
                                    val buf = sessionLogBuffers.getOrPut(sId) { ConcurrentLinkedQueue() }
                                    if (buf.size < 2000) {
                                        buf.add(log)
                                    }
                                }
                            }
                        }, null)
                        
                        activeSessions.add(asyncSession)
                        
                        cont.invokeOnCancellation {
                            Log.d(TAG, "Coroutine cancelled. Terminating FFmpeg session: ${asyncSession.sessionId}")
                            FFmpegKit.cancel(asyncSession.sessionId)
                            activeSessions.remove(asyncSession)
                            sessionLogBuffers.remove(asyncSession.sessionId)
                        }
                    }
                }

                val session = if (isHardwareCodec) {
                    com.eclip.rup.leb.utils.CodecInstancePool.withHardwareCodecPermit {
                        sessionBlock()
                    }
                } else {
                    sessionBlock()
                }

                activeSessions.remove(session)

                val returnCode = session.getReturnCode()
                Log.d(TAG, "FFmpeg completed with return code: $returnCode")

                val logs = sessionLogBuffers.remove(session.sessionId)?.toList()
                val outputPath = extractOutputPath(command)
                val missingVideo = isMissingVideoStream(command, logs, outputPath)

                if (ReturnCode.isSuccess(returnCode) && !missingVideo) {
                    RenderResult.Success(
                        outputPath = outputPath,
                        session = session
                    )
                } else {
                    cleanupFailedSession(session)

                    val diagnosticLog = createDiagnosticReport(context, command, returnCode, session, logs)

                    if (isHardwareEncoderFailure(command, returnCode, logs, missingVideo)) {
                        val retryResult = tryHardwareEncode(command, retryLevel, diagnosticLog) { cmd, lvl ->
                            executeCommand(cmd, lvl)
                        }
                        if (retryResult != null) return@withContext retryResult
                    }

                    Log.e(TAG, "FFmpeg error (non-recoverable):\n$diagnosticLog")
                    val userMsg = if (retryLevel >= 2) {
                        "वीडियो प्रोसेसिंग पूरी तरह विफल रही। डिवाइस का हार्डवेयर और सॉफ़्टवेयर एनकोडर दोनों काम नहीं कर रहे हैं।"
                    } else {
                        "वीडियो प्रोसेसिंग में तकनीकी त्रुटि आई। (Error Code: ${returnCode?.getValue() ?: "Unknown"})"
                    }
                    RenderResult.Failure(error = diagnosticLog, userMessage = userMsg, session = session)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during FFmpeg execution: ${e.message}", e)
                val diagnosticLog = createDiagnosticReport(context, command, null, null, null, e)
                RenderResult.Failure(error = diagnosticLog, userMessage = "प्रोसेसिंग के दौरान अप्रत्याशित त्रुटि आई।")
            }
        }
    }

    // ── Clean Architecture Helpers ────────────────────────────────────────────

    private suspend fun tryHardwareEncode(
        command: String,
        retryLevel: Int,
        diagnosticLog: String,
        executor: suspend (String, Int) -> RenderResult
    ): RenderResult? {
        val fallbackCommand = getFallbackCommand(command, retryLevel) ?: return null
        
        if (fallbackCommand.contains("libx264")) {
            Log.w(TAG, "Hardware encoder failed. Terminating process and falling back to software encoder libx264. Error:\n$diagnosticLog")
        } else {
            Log.w(TAG, "Hardware encoder hevc_mediacodec failed. Terminating process and falling back to h264_mediacodec. Error:\n$diagnosticLog")
        }
        
        return executor(fallbackCommand, retryLevel + 1)
    }

    private fun getFallbackCommand(command: String, retryLevel: Int): String? {
        if (retryLevel == 0 && command.contains("hevc_mediacodec")) {
            return command.replace("hevc_mediacodec", "h264_mediacodec")
        } else if (retryLevel <= 1 && command.contains("h264_mediacodec")) {
            return fallbackToSoftwareEncode(command)
        }
        return null
    }

    private fun fallbackToSoftwareEncode(command: String): String {
        return if (!command.contains("-preset")) {
            command.replace("-c:v h264_mediacodec -b:v 2M", "-c:v libx264 -preset ultrafast -tune fastdecode -crf 26")
                .replace("-c:v h264_mediacodec", "-c:v libx264 -preset ultrafast")
                .replace("h264_mediacodec", "libx264")
        } else {
            command.replace("h264_mediacodec", "libx264")
        }
    }

    private fun cleanupFailedSession(session: FFmpegSession) {
        FFmpegKit.cancel(session.sessionId)
        activeSessions.remove(session)
    }

    // ── Session management ────────────────────────────────────────────────────

    suspend fun cancelAllSessions() {
        withContext(Dispatchers.IO) {
            FFmpegKit.cancel()
            activeSessions.clear()
            sessionLogBuffers.clear()
            Log.d(TAG, "Cancelled all FFmpeg sessions")
        }
    }

    suspend fun cancelSession(sessionId: Long) {
        withContext(Dispatchers.IO) {
            FFmpegKit.cancel(sessionId)
            activeSessions.removeIf { it.sessionId == sessionId }
            sessionLogBuffers.remove(sessionId)
            Log.d(TAG, "Cancelled session: $sessionId")
        }
    }

    fun hasActiveSessions(): Boolean = activeSessions.isNotEmpty()
    fun getActiveSessionCount(): Int = activeSessions.size
    fun getLatestSession(): FFmpegSession? = activeSessions.lastOrNull()

    // ── Preview helpers ───────────────────────────────────────────────────────

    suspend fun renderTrimPreview(
        sourceFilePath: String,
        startMs: Long,
        endMs: Long,
        outputFilePath: String
    ): RenderResult {
        val startSecs = startMs / 1000.0
        val durationSecs = (endMs - startMs) / 1000.0
        val command = "-ss $startSecs -i \"$sourceFilePath\" -to $durationSecs -c copy \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun renderCropPreview(
        sourceFilePath: String,
        aspectRatio: String,
        outputFilePath: String
    ): RenderResult {
        val cropFilter = buildCropFilter(aspectRatio)
            ?: return RenderResult.Failure("Invalid aspect ratio: $aspectRatio")
        val command = "-hwaccel mediacodec -i \"$sourceFilePath\" -vf \"$cropFilter\" -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }

    /**
     * Render a preview with a text overlay.
     *
     * @param fontFilePath  Absolute path to a TTF file. Use [copyFontToCache] to get this.
     */
    suspend fun renderTextPreview(
        sourceFilePath: String,
        text: String,
        fontSize: Int,
        positionParam: String,
        outputFilePath: String,
        fontFilePath: String? = null
    ): RenderResult {
        val textFilter = buildDrawtextFilter(text, fontSize, positionParam, fontFilePath)
        val command = "-hwaccel mediacodec -i \"$sourceFilePath\" -vf \"$textFilter\" -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }

    // ── Full-quality operations ───────────────────────────────────────────────

    /** Export the final video using the consolidated command from the ViewModel. */
    suspend fun exportFinal(
        ffmpegCommand: String,
        totalDurationSecs: Double? = null,
        onProgress: ((Int) -> Unit)? = null,
        retryLevel: Int = 0
    ): RenderResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Executing FFmpeg command: $ffmpegCommand")
                
                val session = suspendCancellableCoroutine<FFmpegSession> { cont ->
                    val asyncSession = FFmpegKit.executeAsync(ffmpegCommand, { completeSession ->
                        cont.resume(completeSession)
                    }, { log ->
                        val msg = log.message?.trimEnd() ?: return@executeAsync
                        if (msg.isNotEmpty()) {
                            val sId = log.sessionId
                            if (sId != 0L) {
                                val buf = sessionLogBuffers.getOrPut(sId) { ConcurrentLinkedQueue() }
                                if (buf.size < 2000) {
                                    buf.add(log)
                                }
                            }
                            Log.d(TAG, "[ffmpeg] $msg")
                        }
                    }, { statistics ->
                        if (totalDurationSecs != null && totalDurationSecs > 0) {
                            val timeMs = statistics.time
                            if (timeMs > 0) {
                                val timeSecs = timeMs.toDouble() / 1000.0
                                val progress = (timeSecs / totalDurationSecs * 100).toInt()
                                onProgress?.invoke(progress.coerceIn(0, 100))
                            }
                        }
                    })
                    activeSessions.add(asyncSession)
                    
                    cont.invokeOnCancellation {
                        FFmpegKit.cancel(asyncSession.sessionId)
                        activeSessions.remove(asyncSession)
                        sessionLogBuffers.remove(asyncSession.sessionId)
                    }
                }
                
                activeSessions.remove(session)

                val returnCode = session.getReturnCode()
                Log.d(TAG, "FFmpeg completed with return code: ${returnCode?.getValue()}")

                val logs = sessionLogBuffers.remove(session.sessionId)?.toList()
                val outputPath = extractOutputPath(ffmpegCommand)
                val missingVideo = isMissingVideoStream(ffmpegCommand, logs, outputPath)

                if (ReturnCode.isSuccess(returnCode) && !missingVideo) {
                    RenderResult.Success(
                        outputPath = outputPath,
                        session = session
                    )
                } else {
                    cleanupFailedSession(session)

                    val diagnosticLog = createDiagnosticReport(context, ffmpegCommand, returnCode, session, logs)

                    if (isHardwareEncoderFailure(ffmpegCommand, returnCode, logs, missingVideo)) {
                        val retryResult = tryHardwareEncode(ffmpegCommand, retryLevel, diagnosticLog) { cmd, lvl ->
                            exportFinal(cmd, totalDurationSecs, onProgress, lvl)
                        }
                        if (retryResult != null) return@withContext retryResult
                    }

                    Log.e(TAG, "FFmpeg error (non-recoverable):\n$diagnosticLog")
                    val userMsg = if (retryLevel >= 2) {
                        "वीडियो एक्सपोर्ट पूरी तरह विफल रहा। डिवाइस का हार्डवेयर और सॉफ़्टवेयर एनकोडर दोनों काम नहीं कर रहे हैं।"
                    } else {
                        "वीडियो एक्सपोर्ट में तकनीकी त्रुटि आई। (Error Code: ${returnCode?.getValue() ?: "Unknown"})"
                    }
                    RenderResult.Failure(error = diagnosticLog, userMessage = userMsg, session = session)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during FFmpeg execution: ${e.message}", e)
                val diagnosticLog = createDiagnosticReport(context, ffmpegCommand, null, null, null, e)
                RenderResult.Failure(error = diagnosticLog, userMessage = "एक्सपोर्ट के दौरान अप्रत्याशित त्रुटि आई।")
            }
        }
    }

    private fun isHardwareEncoderFailure(
        command: String,
        returnCode: com.antonkarpenko.ffmpegkit.ReturnCode?,
        logs: List<com.antonkarpenko.ffmpegkit.Log>?,
        missingVideo: Boolean
    ): Boolean {
        if (!command.contains("hevc_mediacodec") && !command.contains("h264_mediacodec")) {
            return false
        }
        
        if (missingVideo) return true

        var hasHwError = false
        if (!logs.isNullOrEmpty()) {
            val hwFailureKeywords = listOf(
                "MediaCodec",
                "CodecException",
                "codec configure failure",
                "encoder unavailable",
                "encoder start failure",
                "unsupported codec",
                "unsupported profile",
                "unsupported level",
                "output initialization failure",
                "Error while opening encoder",
                "Error initializing output stream"
            )
            val genericFailureKeywords = listOf(
                "No such file or directory",
                "Permission denied",
                "Invalid data found when processing input",
                "moov atom not found"
            )
            
            for (l in logs) {
                val msg = l.message ?: continue
                
                for (keyword in genericFailureKeywords) {
                    if (msg.contains(keyword, ignoreCase = true)) {
                        return false // Immediately abort fallback, it's a generic I/O error, not HW failure
                    }
                }
                
                for (keyword in hwFailureKeywords) {
                    if (msg.contains(keyword, ignoreCase = true)) {
                        hasHwError = true
                    }
                }
            }
        }
        
        if (hasHwError) return true
        
        // If it failed with a non-success code, and we didn't find any generic I/O error,
        // assume it's a native encoder crash.
        if (returnCode != null && !com.antonkarpenko.ffmpegkit.ReturnCode.isSuccess(returnCode)) {
            return true
        }
        
        return false
    }

    private fun isMissingVideoStream(command: String, logs: List<com.antonkarpenko.ffmpegkit.Log>?, outputPath: String): Boolean {
        val isVideoExport = (command.contains("-c:v") || command.contains("h264_mediacodec") || command.contains("libx264")) && !command.contains("-vn")
        if (!isVideoExport) return false

        if (!logs.isNullOrEmpty()) {
            for (l in logs) {
                val msg = l.message ?: continue
                if (msg.contains("video:0kB", ignoreCase = true) ||
                    msg.contains("video:0.0kB", ignoreCase = true) ||
                    msg.contains("video: 0kB", ignoreCase = true) ||
                    msg.contains("video:0B", ignoreCase = true) ||
                    (msg.contains("frame=") && msg.contains("frame= 0 ") && msg.contains("Lsize="))
                ) {
                    Log.w(TAG, "isMissingVideoStream: Detected zero video frames in FFmpeg log line: $msg")
                    return true
                }
            }
        }

        if (outputPath.isNotBlank()) {
            val file = File(outputPath)
            if (!file.exists() || file.length() == 0L) {
                Log.w(TAG, "isMissingVideoStream: Output file is missing or 0 bytes: $outputPath")
                return true
            }
        }

        return false
    }

    /**
     * Create a structured technical diagnostic report for failed operations.
     */
    private fun createDiagnosticReport(
        context: Context,
        command: String,
        returnCode: ReturnCode?,
        session: FFmpegSession?,
        collectedLogs: List<com.antonkarpenko.ffmpegkit.Log>?,
        exception: Throwable? = null
    ): String {
        val appVersion = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val vCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pInfo)
            "${pInfo.versionName} ($vCode)"
        } catch (e: Exception) {
            "1.0-beta"
        }

        val failStackTrace = session?.failStackTrace?.takeIf { it.isNotBlank() }

        val rawLogLines = mutableListOf<String>()
        if (!collectedLogs.isNullOrEmpty()) {
            for (l in collectedLogs) {
                l.message?.trimEnd()?.let { if (it.isNotEmpty()) rawLogLines.add(it) }
            }
        } else {
            session?.allLogsAsString?.takeIf { it.isNotBlank() }?.let { allLogsStr ->
                rawLogLines.addAll(allLogsStr.split("\n").map { it.trimEnd() }.filter { it.isNotEmpty() })
            }
        }

        val errorKeywords = listOf(
            "error", "fatal", "panic", "failed", "invalid", "cannot",
            "denied", "not found", "no such", "exception", "aborted",
            "conversion failed", "option not found", "filtergraph", "could not"
        )

        val keyErrors = mutableListOf<String>()
        val cleanHistory = mutableListOf<String>()

        for (line in rawLogLines) {
            val lower = line.lowercase()
            val isProgress = line.startsWith("frame=") || line.startsWith("size=") ||
                    (line.contains("fps=") && line.contains("time="))
            val isErrorLine = errorKeywords.any { lower.contains(it) }

            if (isErrorLine && !isProgress) {
                keyErrors.add(line)
            }
            if (!isProgress || isErrorLine) {
                cleanHistory.add(line)
            }
        }

        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("                ECLIP TECHNICAL LOG                ")
        sb.appendLine("==================================================")
        sb.appendLine("App Version : $appVersion")
        sb.appendLine("Device      : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Return Code : ${returnCode?.getValue() ?: "N/A"}")
        if (exception != null) {
            sb.appendLine("Exception   : ${exception.javaClass.simpleName} - ${exception.message}")
        }
        sb.appendLine()
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("EXECUTED COMMAND:")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine(command)
        sb.appendLine()

        if (!failStackTrace.isNullOrEmpty()) {
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("JAVA FAIL STACK TRACE:")
            sb.appendLine("--------------------------------------------------")
            sb.appendLine(failStackTrace)
            sb.appendLine()
        }

        if (keyErrors.isNotEmpty()) {
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("CRITICAL ERROR MESSAGES (${keyErrors.size}):")
            sb.appendLine("--------------------------------------------------")
            keyErrors.take(25).forEach { sb.appendLine(it) }
            sb.appendLine()
        }

        sb.appendLine("--------------------------------------------------")
        sb.appendLine("RELEVANT LOG TRAIL (${cleanHistory.size} lines):")
        sb.appendLine("--------------------------------------------------")
        if (cleanHistory.isEmpty()) {
            sb.appendLine("(No log lines outputted by FFmpeg)")
        } else {
            cleanHistory.takeLast(100).forEach { sb.appendLine(it) }
        }
        sb.appendLine("==================================================")

        return sb.toString()
    }

    /** Extract audio from a video file using FFmpeg. */
    suspend fun extractAudio(
        sourceFilePath: String,
        outputFilePath: String
    ): RenderResult {
        // -vn disables video stream, -acodec copy copies the audio stream.
        val command = "-y -i \"$sourceFilePath\" -vn -acodec copy \"$outputFilePath\""
        return executeCommand(command)
    }

    /**
     * Merge videos using the FFmpeg concat demuxer.
     * FIX: Both listFilePath and outputFilePath are quoted.
     */
    suspend fun mergeVideos(
        listFilePath: String,
        outputFilePath: String
    ): RenderResult {
        val command = "-f concat -safe 0 -i \"$listFilePath\" -c:v copy -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun trimVideo(
        sourceFilePath: String,
        startMs: Long,
        endMs: Long,
        outputFilePath: String
    ): RenderResult {
        val startSecs = startMs / 1000.0
        val durationSecs = (endMs - startMs) / 1000.0
        val command = "-ss $startSecs -i \"$sourceFilePath\" -to $durationSecs -c copy \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun generateSpeedProxy(
        sourceFilePath: String,
        startMs: Long,
        endMs: Long,
        speed: Float,
        outputFilePath: String
    ): RenderResult {
        val startSecs = startMs / 1000.0
        val durationSecs = (endMs - startMs) / 1000.0
        
        val ptsMultiplier = 1.0f / speed
        
        val atempoFilters = mutableListOf<String>()
        var currentSpeed = speed
        while (currentSpeed < 0.5f) {
            atempoFilters.add("atempo=0.5")
            currentSpeed /= 0.5f
        }
        while (currentSpeed > 2.0f) {
            atempoFilters.add("atempo=2.0")
            currentSpeed /= 2.0f
        }
        if (currentSpeed != 1.0f || atempoFilters.isEmpty()) {
            atempoFilters.add("atempo=$currentSpeed")
        }
        val audioFilter = atempoFilters.joinToString(",")
        
        val command = "-y -ss $startSecs -t $durationSecs -hwaccel mediacodec -i \"$sourceFilePath\" -filter:v \"setpts=${ptsMultiplier}*PTS,format=yuv420p\" -filter:a \"$audioFilter\" \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun generateScrubProxy(
        sourceFilePath: String,
        outputFilePath: String
    ): RenderResult {
        // Fast proxy generation: Downscale to 480p (keeping aspect ratio with even dimensions),
        // Short-GOP (-g 5) for instant I-frame / key-frame seek responsiveness.
        // Primary: Hardware encoder (hevc_mediacodec) for minimal CPU load.
        // Fallback: handled automatically by executeCommand
        val command = "-y -threads 0 -hwaccel mediacodec -i \"$sourceFilePath\" -vf \"scale='min(854,iw)':-2,scale=trunc(iw/2)*2:trunc(ih/2)*2\" -c:v hevc_mediacodec -b:v 2M -g 5 -keyint_min 5 -sc_threshold 0 -pix_fmt yuv420p -c:a aac -b:a 128k -movflags +faststart \"$outputFilePath\""
        return executeCommand(command)
    }


    suspend fun reverseVideo(
        sourceFilePath: String,
        startMs: Long,
        endMs: Long,
        outputFilePath: String
    ): RenderResult {
        val startSecs = startMs / 1000.0
        val durationSecs = (endMs - startMs) / 1000.0
        val command = "-y -ss $startSecs -hwaccel mediacodec -i \"$sourceFilePath\" -to $durationSecs -filter:v \"reverse,format=yuv420p\" -filter:a \"areverse\" -c:v libx264 -c:a aac \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun cropVideo(
        sourceFilePath: String,
        aspectRatio: String,
        outputFilePath: String
    ): RenderResult {
        val cropFilter = buildCropFilter(aspectRatio)
            ?: return RenderResult.Failure("Invalid aspect ratio: $aspectRatio")
        val command = "-hwaccel mediacodec -i \"$sourceFilePath\" -vf \"$cropFilter\" -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }

    /**
     * Add a text overlay to a video.
     *
     * @param fontFilePath  Absolute path to a TTF file. Use [copyFontToCache] to get this.
     */
    suspend fun addTextOverlay(
        sourceFilePath: String,
        text: String,
        fontSize: Int,
        positionParam: String,
        outputFilePath: String,
        fontFilePath: String? = null
    ): RenderResult {
        val textFilter = buildDrawtextFilter(text, fontSize, positionParam, fontFilePath)
        val command = "-hwaccel mediacodec -i \"$sourceFilePath\" -vf \"$textFilter\" -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun addBackgroundAudio(
        sourceFilePath: String,
        audioFilePath: String,
        outputFilePath: String,
        replaceAudio: Boolean = false
    ): RenderResult {
        val command = if (replaceAudio) {
            "-i \"$sourceFilePath\" -i \"$audioFilePath\" -c:v copy -map 0:v:0 -map 1:a:0 \"$outputFilePath\""
        } else {
            "-i \"$sourceFilePath\" -i \"$audioFilePath\" " +
                    "-filter_complex \"[0:a][1:a]amix=inputs=2:duration=first[a]\" " +
                    "-map 0:v -map \"[a]\" -c:v copy \"$outputFilePath\""
        }
        return executeCommand(command)
    }

    suspend fun muteAudio(sourceFilePath: String, outputFilePath: String): RenderResult {
        val command = "-i \"$sourceFilePath\" -an -c:v copy \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun extractFrame(
        sourceFilePath: String,
        timeMs: Long,
        outputImagePath: String
    ): RenderResult {
        val timeSecs = timeMs / 1000.0
        val command = "-ss $timeSecs -hwaccel mediacodec -i \"$sourceFilePath\" -vframes 1 \"$outputImagePath\""
        return executeCommand(command)
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun cleanup() {
        activeSessions.clear()
        Log.d(TAG, "FFmpegRenderEngine cleaned up")
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Build a drawtext filter string.
     * Uses fontfile= (not the non-existent font= option).
     */
    private fun buildDrawtextFilter(
        text: String,
        fontSize: Int,
        positionParam: String,
        fontFilePath: String?
    ): String {
        val escapedText = text
            .replace("\\", "\\\\")
            .replace("'", "\\\\'")
            .replace(":", "\\:")

        val fontPart = if (!fontFilePath.isNullOrBlank()) {
            val escapedFontPath = fontFilePath
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace(":", "\\:")
            "fontfile='$escapedFontPath':"
        } else {
            Log.w(TAG, "No fontFilePath — text may not render. Call copyFontToCache() first.")
            ""
        }

        return "drawtext=${fontPart}text='$escapedText':fontcolor=white:fontsize=$fontSize:$positionParam"
    }

    private fun buildCropFilter(aspectRatio: String): String? = when (aspectRatio) {
        "16:9" -> "crop='trunc(min(iw\\,ih*16/9)/2)*2':'trunc(min(ih\\,iw*9/16)/2)*2',setsar=1"
        "9:16" -> "crop='trunc(min(iw\\,ih*9/16)/2)*2':'trunc(min(ih\\,iw*16/9)/2)*2',setsar=1"
        "1:1"  -> "crop='trunc(min(iw\\,ih)/2)*2':'trunc(min(iw\\,ih)/2)*2',setsar=1"
        else   -> null
    }

    /**
     * Extract the output file path from an FFmpeg command.
     *
     * FIX: Handles both quoted paths (standard) and unquoted paths (legacy merge commands).
     * Previously only matched quoted strings, which caused truncated paths when the output
     * was accidentally left unquoted — producing errors like:
     *   "Unable to find a suitable output format for '/data/user/0/com.eclip.rup.leb'"
     */
    suspend fun generateVideoFromImage(
        imageUri: Uri,
        durationMs: Long,
        outputPath: String
    ): RenderResult {
        // getSafParameterForRead returns saf:X which confuses the image2 demuxer.
        // We force copy to a cache file so FFmpeg sees a standard file path.
        val imagePath = resolveUriToFilePath(imageUri, forceCopy = true)
            ?: return RenderResult.Failure("Could not read image file. URI: $imageUri")
        val durationSecs = durationMs / 1000f

        // Use 30 fps with hardware acceleration (h264_mediacodec) for instant encoding, falling back to libx264 if needed.
        // The scale filter ensures the width and height are divisible by 2.
        val command = "-f image2 -loop 1 -framerate 30 -i \"$imagePath\" -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 -vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" -c:v hevc_mediacodec -b:v 4M -t $durationSecs -pix_fmt yuv420p -c:a aac -shortest \"$outputPath\""
        return executeCommand(command)
    }

    suspend fun generateVideoFromGif(
        gifUri: Uri,
        durationMs: Long,
        outputPath: String
    ): RenderResult {
        val gifPath = resolveUriToFilePath(gifUri, forceCopy = true)
            ?: return RenderResult.Failure("Could not read GIF file. URI: $gifUri")
        val durationSecs = durationMs / 1000f

        // ignore_loop 0 loops the gif infinitely. We then add a silent audio track and limit duration.
        // Try hardware encoder first for fast generation.
        val command = "-f gif -ignore_loop 0 -i \"$gifPath\" -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 -vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" -c:v hevc_mediacodec -b:v 4M -t $durationSecs -pix_fmt yuv420p -c:a aac -shortest \"$outputPath\""
        return executeCommand(command)
    }

    /**
     * Resolve any URI to a plain file-system path that FFmpeg can read.
     *
     * - file:// URIs  → strip scheme, return path directly
     * - content:// SAF URIs (ACTION_OPEN_DOCUMENT) → use getSafParameterForRead
     * - content:// MediaStore URIs → copy to a temp file in cacheDir
     */
    private suspend fun resolveUriToFilePath(uri: Uri, forceCopy: Boolean = false): String? = withContext(Dispatchers.IO) {
        when (uri.scheme) {
            "file" -> uri.path
            "content" -> {
                if (!forceCopy) {
                    // Try SAF first (works for ACTION_OPEN_DOCUMENT URIs, but custom protocol can confuse some demuxers)
                    val safPath = try { FFmpegKitConfig.getSafParameterForRead(context, uri) } catch (e: Exception) { null }
                    if (!safPath.isNullOrBlank()) return@withContext safPath
                }

                // Fallback (or forced): copy the stream to a temp cache file
                try {
                    // Determine a sensible extension from the MIME type
                    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val ext = when {
                        mimeType.contains("gif")  -> ".gif"
                        mimeType.contains("png")  -> ".png"
                        mimeType.contains("webp") -> ".webp"
                        mimeType.contains("heic") || mimeType.contains("heif") -> ".heic"
                        else -> ".jpg"
                    }
                    val tempFile = File(context.cacheDir, "img_input_${System.currentTimeMillis()}$ext")
                    val inputStream = if (uri.scheme == "file") {
                        java.io.FileInputStream(uri.path!!)
                    } else {
                        context.contentResolver.openInputStream(uri)
                    } ?: return@withContext null
                    inputStream.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (tempFile.length() == 0L) { tempFile.delete(); return@withContext null }
                    Log.d(TAG, "resolveUriToFilePath: copied to ${tempFile.absolutePath} (${tempFile.length()} bytes)")
                    tempFile.absolutePath
                } catch (e: Exception) {
                    Log.e(TAG, "resolveUriToFilePath: failed to copy URI $uri: ${e.message}", e)
                    null
                }
            }
            else -> null
        }
    }

    private fun extractOutputPath(command: String): String {
        // Try quoted path first (preferred — all new commands use this)
        val quotedRegex = """"([^"]*)"\s*$""".toRegex()
        quotedRegex.find(command)?.groupValues?.get(1)?.let { return it }

        // Fallback: last whitespace-delimited token (for unquoted legacy commands)
        return command.trimEnd().split("\\s+".toRegex()).lastOrNull() ?: ""
    }
}