package com.eclip.rup.leb.utils

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Robust, deep media validator that inspects actual container format, MIME types,
 * video/audio tracks, codecs, and hardware decoding capabilities.
 *
 * Avoids false "corrupted" or "unsupported" errors for valid MP4/media files.
 */
object MediaValidator {

    private const val TAG = "MediaValidator"

    const val MAX_SUPPORTED_WIDTH = 1920
    const val MAX_SUPPORTED_HEIGHT = 1080
    const val MSG_UNSUPPORTED_RESOLUTION =
        "यह ऐप केवल 1080p तक के वीडियो को support करता है। 1080p से अधिक resolution वाली वीडियो, जैसे 2K या 4K, supported नहीं हैं।"

    fun isResolutionAbove1080p(width: Int, height: Int): Boolean {
        val maxDim = maxOf(width, height)
        val minDim = minOf(width, height)
        return maxDim > MAX_SUPPORTED_WIDTH || minDim > MAX_SUPPORTED_HEIGHT
    }

    sealed class ValidationResult {
        data class Valid(
            val width: Int,
            val height: Int,
            val durationMs: Long,
            val rotation: Int,
            val bitrate: Long,
            val videoMime: String,
            val audioMime: String?,
            val hasVideoTrack: Boolean,
            val hasAudioTrack: Boolean,
            val isExceedingHardwareLimits: Boolean,
            val containerFormat: String,
            val frameRate: Float = 30f
        ) : ValidationResult()

        data class RequiresDownscale(
            val originalWidth: Int,
            val originalHeight: Int,
            val targetWidth: Int,
            val targetHeight: Int,
            val durationMs: Long,
            val rotation: Int,
            val bitrate: Long,
            val videoMime: String,
            val audioMime: String?,
            val hasVideoTrack: Boolean,
            val hasAudioTrack: Boolean,
            val frameRate: Float = 30f
        ) : ValidationResult()

        data class UnsupportedResolution(
            val width: Int,
            val height: Int,
            val userFriendlyMessage: String = MSG_UNSUPPORTED_RESOLUTION
        ) : ValidationResult()

        data class Invalid(
            val userFriendlyMessage: String,
            val technicalDetails: String
        ) : ValidationResult()
    }

    private val hardwareLimitCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Validates a media file from URI or local file path thoroughly.
     */
    fun validateMedia(context: Context, uri: Uri): ValidationResult {
        var pfd: android.os.ParcelFileDescriptor? = null
        val extractor = MediaExtractor()
        var retriever: MediaMetadataRetriever? = null

        fun getOrInitRetriever(): MediaMetadataRetriever {
            val existing = retriever
            if (existing != null) return existing
            val r = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file" || uri.scheme == null) {
                    val path = uri.path ?: ""
                    r.setDataSource(path)
                } else if (pfd != null) {
                    r.setDataSource(pfd!!.fileDescriptor)
                } else {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { tempPfd ->
                        r.setDataSource(tempPfd.fileDescriptor)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize retriever fallback for $uri: ${e.message}")
            }
            retriever = r
            return r
        }

        try {
            // 1. Basic File / Stream Accessibility
            if (uri.scheme == "file" || uri.scheme == null) {
                val path = uri.path
                if (path.isNullOrBlank()) {
                    return ValidationResult.Invalid("Invalid file path.", "Path is null or blank.")
                }
                val file = File(path)
                if (!file.exists()) {
                    return ValidationResult.Invalid("File does not exist.", "File path not found: $path")
                }
                if (file.length() <= 0L) {
                    return ValidationResult.Invalid("Video file is empty (0 bytes).", "File length is 0 bytes: $path")
                }
                extractor.setDataSource(file.absolutePath)
            } else {
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd == null) {
                    return ValidationResult.Invalid("Could not open video file.", "openFileDescriptor returned null for $uri")
                }
                if (pfd!!.statSize == 0L) {
                    return ValidationResult.Invalid("Video file is empty (0 bytes).", "statSize is 0 for $uri")
                }
                extractor.setDataSource(pfd!!.fileDescriptor)
            }

            // 2. Track & Container Inspection via MediaExtractor
            val trackCount = extractor.trackCount
            if (trackCount == 0) {
                return ValidationResult.Invalid(
                    "No media tracks found in file.",
                    "MediaExtractor reported 0 tracks for $uri"
                )
            }

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                    videoFormat = format
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i
                    audioFormat = format
                }
            }

            if (videoTrackIndex == -1) {
                // If extractor couldn't find a video track, check retriever before failing
                val hasVideo = getOrInitRetriever().extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                if (hasVideo != "yes") {
                    return ValidationResult.Invalid(
                        "No video stream found in this file.",
                        "Neither MediaExtractor nor MediaMetadataRetriever found a video track."
                    )
                }
            }

            // 3. Extract Metadata (Fast-path: read directly from MediaFormat without spawning Stagefright retriever)
            val extractedWidth = videoFormat?.getIntegerSafe(MediaFormat.KEY_WIDTH)
            val extractedHeight = videoFormat?.getIntegerSafe(MediaFormat.KEY_HEIGHT)
            val extractedDurationMs = videoFormat?.getLongSafe(MediaFormat.KEY_DURATION)?.div(1000L)
            val extractedRotation = videoFormat?.getIntegerSafe(MediaFormat.KEY_ROTATION)

            val width = extractedWidth
                ?: getOrInitRetriever().extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: 1280
            val height = extractedHeight
                ?: getOrInitRetriever().extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: 720
            val durationMs = extractedDurationMs
                ?: getOrInitRetriever().extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: 0L
            val rotation = extractedRotation
                ?: getOrInitRetriever().extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                ?: 0
            val bitrate = videoFormat?.getIntegerSafe(MediaFormat.KEY_BIT_RATE)?.toLong()
                ?: retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                ?: 0L

            val videoMime = videoFormat?.getString(MediaFormat.KEY_MIME)
                ?: retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?: "video/mp4"
            val audioMime = audioFormat?.getString(MediaFormat.KEY_MIME)

            // 4a. Extract frame rate for hardware capability checks
            val frameRate = try {
                videoFormat?.getIntegerSafe(MediaFormat.KEY_FRAME_RATE)?.toFloat()
                    ?: retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                    ?: 30f
            } catch (_: Exception) { 30f }

            // 4. Check if video resolution exceeds maximum 1080p limit
            if (isResolutionAbove1080p(width, height)) {
                val targetDims = calculateTarget1080pDimensions(width, height)
                Log.i(TAG, "Video resolution ${width}x${height} exceeds maximum 1080p limit. Requires downscale to ${targetDims.first}x${targetDims.second}")
                return ValidationResult.RequiresDownscale(
                    originalWidth = width,
                    originalHeight = height,
                    targetWidth = targetDims.first,
                    targetHeight = targetDims.second,
                    durationMs = durationMs,
                    rotation = rotation,
                    bitrate = bitrate,
                    videoMime = videoMime,
                    audioMime = audioMime,
                    hasVideoTrack = true,
                    hasAudioTrack = audioTrackIndex != -1,
                    frameRate = frameRate
                )
            }

            // 5. Check if video resolution+framerate exceeds hardware decoder limits
            val isExceedingHardwareLimits = isResolutionExceedingDeviceLimit(videoMime, width, height, frameRate)

            Log.i(
                TAG,
                "Media validated successfully: $width x $height @ ${frameRate}fps, ${durationMs}ms, videoMime=$videoMime, audioMime=$audioMime, exceedsHardware=$isExceedingHardwareLimits"
            )

            return ValidationResult.Valid(
                width = width,
                height = height,
                durationMs = durationMs,
                rotation = rotation,
                bitrate = bitrate,
                videoMime = videoMime,
                audioMime = audioMime,
                hasVideoTrack = true,
                hasAudioTrack = audioTrackIndex != -1,
                isExceedingHardwareLimits = isExceedingHardwareLimits,
                containerFormat = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4",
                frameRate = frameRate
            )

        } catch (e: Exception) {
            Log.e(TAG, "Media validation error: ${e.message}", e)
            return ValidationResult.Invalid(
                "Unable to read video file: ${e.localizedMessage ?: "Format error"}",
                e.stackTraceToString()
            )
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {}
            try {
                retriever?.release()
            } catch (_: Exception) {}
            try {
                pfd?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Calculates target dimensions capped at 1080p while preserving aspect ratio.
     */
    fun calculateTarget1080pDimensions(width: Int, height: Int): Pair<Int, Int> {
        if (!isResolutionAbove1080p(width, height)) return Pair(width, height)
        val isLandscape = width >= height
        val maxW = if (isLandscape) MAX_SUPPORTED_WIDTH else MAX_SUPPORTED_HEIGHT
        val maxH = if (isLandscape) MAX_SUPPORTED_HEIGHT else MAX_SUPPORTED_WIDTH

        val ratio = width.toFloat() / height.toFloat()
        var targetW = maxW
        var targetH = (targetW / ratio).toInt()

        if (targetH > maxH) {
            targetH = maxH
            targetW = (targetH * ratio).toInt()
        }
        targetW = (targetW / 2) * 2
        targetH = (targetH / 2) * 2
        return Pair(maxOf(targetW, 2), maxOf(targetH, 2))
    }

    /**
     * Checks whether the device's hardware MediaCodec decoders can decode the given resolution AND frame rate.
     * Accurately checks hardware decoders first. If any hardware-accelerated decoder supports the size and rate,
     * it returns false (supported). If only software decoders exist or no decoder supports it, evaluates appropriately.
     */
    fun isResolutionExceedingDeviceLimit(mime: String, width: Int, height: Int, frameRate: Float = 30f): Boolean {
        val cacheKey = "$mime#$width#$height#${frameRate.toInt()}"
        hardwareLimitCache[cacheKey]?.let { return it }

        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var hardwareDecoderFound = false
            var hardwareDecoderSupported = false
            var softwareDecoderSupported = false

            for (codecInfo in codecList.codecInfos) {
                if (codecInfo.isEncoder) continue
                for (supportedType in codecInfo.supportedTypes) {
                    if (supportedType.equals(mime, ignoreCase = true)) {
                        val capabilities = try {
                            codecInfo.getCapabilitiesForType(supportedType)
                        } catch (_: Exception) { null } ?: continue
                        val videoCaps = capabilities.videoCapabilities ?: continue

                        val isSupported = try {
                            videoCaps.areSizeAndRateSupported(width, height, frameRate.toDouble())
                        } catch (_: Exception) {
                            videoCaps.isSizeSupported(width, height)
                        }

                        val isHw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            codecInfo.isHardwareAccelerated
                        } else {
                            val name = codecInfo.name.lowercase()
                            !name.startsWith("omx.google.") && !name.startsWith("c2.android.") && !name.contains("sw")
                        }

                        if (isHw) {
                            hardwareDecoderFound = true
                            if (isSupported) {
                                hardwareDecoderSupported = true
                                // If at least one hardware decoder supports this resolution & framerate, device can play it!
                                hardwareLimitCache[cacheKey] = false
                                return false
                            }
                        } else {
                            if (isSupported) {
                                softwareDecoderSupported = true
                            }
                        }
                    }
                }
            }

            val exceeds = if (hardwareDecoderFound) {
                !hardwareDecoderSupported && !softwareDecoderSupported
            } else {
                !softwareDecoderSupported
            }
            hardwareLimitCache[cacheKey] = exceeds
            return exceeds
        } catch (e: Exception) {
            Log.w(TAG, "Codec capabilities check exception: ${e.message}")
        }
        return false
    }

    private fun MediaFormat.getIntegerSafe(key: String): Int? {
        return try {
            if (containsKey(key)) getInteger(key) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun MediaFormat.getLongSafe(key: String): Long? {
        return try {
            if (containsKey(key)) getLong(key) else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fast, lightweight check to determine if a video's resolution exceeds 1080p (1920x1080).
     * Does not copy files or perform deep track analysis.
     */
    fun checkIsAbove1080p(context: Context, uri: Uri): Boolean {
        val res = getVideoResolution(context, uri) ?: return false
        return isResolutionAbove1080p(res.first, res.second)
    }

    /**
     * Extracts video width and height from URI using MediaMetadataRetriever without full processing.
     */
    fun getVideoResolution(context: Context, uri: Uri): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file" || uri.scheme == null) {
                val path = uri.path ?: return null
                val file = File(path)
                if (!file.exists()) return null
                retriever.setDataSource(file.absolutePath)
            } else {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                } ?: return null
            }
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val width = widthStr?.toIntOrNull() ?: return null
            val height = heightStr?.toIntOrNull() ?: return null
            Pair(width, height)
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract video resolution for $uri: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }
}
