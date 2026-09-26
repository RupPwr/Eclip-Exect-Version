package com.eclip.rup.leb.utils

import android.util.Log
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.analytics.AnalyticsListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostic & Lifecycle Instrumentation for Timeline and Playback Systems.
 *
 * Implements architectural observability for:
 * - MediaCodec lifecycle (CREATE, RELEASE, FLUSH, REUSE) with unique IDs.
 * - AudioTrack lifecycle (CREATE, RELEASE, FLUSH) with unique IDs.
 * - Surface lifecycle (CREATE, RESIZE, DESTROY) with unique IDs.
 * - Timeline Scroll State machine transitions & velocity.
 * - Generation-based seek dispatches and discards.
 * - Diagnostic counters to verify ZERO codec/audio churn during fling.
 */
object TimelineLifecycleLogger {

    private const val TAG = "TimelineLifecycle"

    // --- ID Generators ---
    private val nextCodecId = AtomicInteger(100)
    private val nextAudioTrackId = AtomicInteger(200)
    private val nextSurfaceId = AtomicInteger(300)
    private val nextSeekId = AtomicInteger(400)

    // --- Active Instance Mappings ---
    private val activeCodecMap = ConcurrentHashMap<String, Int>()
    private val activeAudioDecoderMap = ConcurrentHashMap<String, Int>()

    // --- Diagnostic Counters ---
    val videoCodecCreateCount = AtomicInteger(0)
    val videoCodecReleaseCount = AtomicInteger(0)
    val audioDecoderCreateCount = AtomicInteger(0)
    val audioDecoderReleaseCount = AtomicInteger(0)
    val audioTrackCreateCount = AtomicInteger(0)
    val audioTrackReleaseCount = AtomicInteger(0)

    val seeksDispatchedFling = AtomicInteger(0)
    val seeksDispatchedDragging = AtomicInteger(0)
    val seeksDispatchedIdle = AtomicInteger(0)
    val seeksDiscardedStaleGeneration = AtomicInteger(0)
    val droppedFramesTotal = AtomicLong(0)

    var currentScrollGeneration: Long = 0L

    fun resetCounters() {
        videoCodecCreateCount.set(0)
        videoCodecReleaseCount.set(0)
        audioDecoderCreateCount.set(0)
        audioDecoderReleaseCount.set(0)
        audioTrackCreateCount.set(0)
        audioTrackReleaseCount.set(0)
        seeksDispatchedFling.set(0)
        seeksDispatchedDragging.set(0)
        seeksDispatchedIdle.set(0)
        seeksDiscardedStaleGeneration.set(0)
        droppedFramesTotal.set(0)
    }

    // --- MediaCodec Logging ---
    fun logMediaCodecCreate(codecName: String): Int {
        val id = nextCodecId.incrementAndGet()
        activeCodecMap[codecName] = id
        videoCodecCreateCount.incrementAndGet()
        Log.i(TAG, "[MediaCodec:CREATE] id=$id name=$codecName timestamp=${System.currentTimeMillis()} generation=$currentScrollGeneration")
        return id
    }

    fun logMediaCodecRelease(codecName: String): Int {
        val id = activeCodecMap.remove(codecName) ?: nextCodecId.get()
        videoCodecReleaseCount.incrementAndGet()
        Log.i(TAG, "[MediaCodec:RELEASE] id=$id name=$codecName timestamp=${System.currentTimeMillis()} generation=$currentScrollGeneration")
        return id
    }

    fun logMediaCodecFlush(codecId: Int) {
        Log.d(TAG, "[MediaCodec:FLUSH] id=$codecId timestamp=${System.currentTimeMillis()} generation=$currentScrollGeneration")
    }

    // --- AudioTrack Logging ---
    fun logAudioTrackCreate(): Int {
        val id = nextAudioTrackId.incrementAndGet()
        audioTrackCreateCount.incrementAndGet()
        Log.i(TAG, "[AudioTrack:CREATE] id=$id timestamp=${System.currentTimeMillis()} generation=$currentScrollGeneration")
        return id
    }

    fun logAudioTrackRelease(id: Int) {
        audioTrackReleaseCount.incrementAndGet()
        Log.i(TAG, "[AudioTrack:RELEASE] id=$id timestamp=${System.currentTimeMillis()} generation=$currentScrollGeneration")
    }

    fun logAudioTrackFlush(id: Int) {
        Log.d(TAG, "[AudioTrack:FLUSH] id=$id timestamp=${System.currentTimeMillis()} generation=$currentScrollGeneration")
    }

    // --- Surface Logging ---
    fun logSurfaceCreate(width: Int, height: Int): Int {
        val id = nextSurfaceId.incrementAndGet()
        Log.i(TAG, "[Surface:CREATE] id=$id size=${width}x${height} timestamp=${System.currentTimeMillis()}")
        return id
    }

    fun logSurfaceDestroy(id: Int) {
        Log.i(TAG, "[Surface:DESTROY] id=$id timestamp=${System.currentTimeMillis()}")
    }

    // --- Scroll State Machine Logging ---
    fun logScrollStateChanged(
        oldState: TimelineScrollState,
        newState: TimelineScrollState,
        generation: Long,
        positionMs: Long,
        velocityPxSec: Float
    ) {
        currentScrollGeneration = generation
        Log.i(TAG, "[TimelineScroll:STATE] $oldState -> $newState generation=$generation posMs=$positionMs vel=${velocityPxSec.toInt()}px/s")
    }

    // --- Preview Seek Logging ---
    fun logPreviewSeekDispatch(
        generation: Long,
        positionMs: Long,
        scrollState: TimelineScrollState,
        isScrubbing: Boolean,
        force: Boolean
    ): Int {
        val seekId = nextSeekId.incrementAndGet()
        when (scrollState) {
            TimelineScrollState.SETTLING -> seeksDispatchedFling.incrementAndGet()
            TimelineScrollState.DRAGGING -> seeksDispatchedDragging.incrementAndGet()
            TimelineScrollState.IDLE -> seeksDispatchedIdle.incrementAndGet()
        }
        Log.d(TAG, "[PreviewSeek:DISPATCH] seekId=$seekId gen=$generation posMs=$positionMs state=$scrollState isScrubbing=$isScrubbing force=$force")
        return seekId
    }

    fun logPreviewSeekDiscard(
        requestGeneration: Long,
        currentGeneration: Long,
        positionMs: Long,
        reason: String
    ) {
        seeksDiscardedStaleGeneration.incrementAndGet()
        Log.w(TAG, "[PreviewSeek:DISCARD] requestGen=$requestGeneration currentGen=$currentGeneration posMs=$positionMs reason='$reason'")
    }

    // --- Thumbnail Logging ---
    fun logThumbnailRequest(key: String, generation: Long, priority: ThumbnailPriority) {
        Log.d(TAG, "[Thumbnail:REQUEST] key=$key gen=$generation priority=$priority")
    }

    fun logThumbnailDiscard(key: String, requestGen: Long, currentGen: Long) {
        Log.d(TAG, "[Thumbnail:DISCARD] key=$key requestGen=$requestGen currentGen=$currentGen (Stale)")
    }

    // --- Diagnostic Report ---
    fun getDiagnosticSummary(): String {
        return buildString {
            appendLine("=== Timeline & Playback Lifecycle Diagnostic Summary ===")
            appendLine("MediaCodec: Created=${videoCodecCreateCount.get()}, Released=${videoCodecReleaseCount.get()}")
            appendLine("AudioDecoder: Created=${audioDecoderCreateCount.get()}, Released=${audioDecoderReleaseCount.get()}")
            appendLine("AudioTrack: Created=${audioTrackCreateCount.get()}, Released=${audioTrackReleaseCount.get()}")
            appendLine("Preview Seeks: Idle=${seeksDispatchedIdle.get()}, Dragging=${seeksDispatchedDragging.get()}, Fling(Must be 0)=${seeksDispatchedFling.get()}")
            appendLine("Stale Seeks Discarded: ${seeksDiscardedStaleGeneration.get()}")
            appendLine("Dropped Frames Total: ${droppedFramesTotal.get()}")
            appendLine("=========================================================")
        }
    }

    /**
     * Pre-configured ExoPlayer [AnalyticsListener] to monitor native decoder & sink lifecycles.
     */
    fun createExoAnalyticsListener(): AnalyticsListener {
        return object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                val codecId = logMediaCodecCreate(decoderName)
                Log.d(TAG, "[ExoPlayer:VideoDecoderInit] id=$codecId name=$decoderName initDurationMs=$initializationDurationMs")
            }

            override fun onVideoDecoderReleased(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String
            ) {
                val codecId = logMediaCodecRelease(decoderName)
                Log.d(TAG, "[ExoPlayer:VideoDecoderRelease] id=$codecId name=$decoderName")
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                val id = nextAudioTrackId.incrementAndGet()
                activeAudioDecoderMap[decoderName] = id
                audioDecoderCreateCount.incrementAndGet()
                Log.i(TAG, "[ExoPlayer:AudioDecoderInit] id=$id name=$decoderName durationMs=$initializationDurationMs")
            }

            override fun onAudioDecoderReleased(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String
            ) {
                val id = activeAudioDecoderMap.remove(decoderName) ?: nextAudioTrackId.get()
                audioDecoderReleaseCount.incrementAndGet()
                Log.i(TAG, "[ExoPlayer:AudioDecoderRelease] id=$id name=$decoderName")
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                droppedFramesTotal.addAndGet(droppedFrames.toLong())
                Log.w(TAG, "[ExoPlayer:DroppedFrames] count=$droppedFrames elapsedMs=$elapsedMs total=${droppedFramesTotal.get()}")
            }

            override fun onPositionDiscontinuity(
                eventTime: AnalyticsListener.EventTime,
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                val reasonStr = when (reason) {
                    Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
                    Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
                    Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
                    Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
                    Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
                    Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
                    else -> "UNKNOWN($reason)"
                }
                Log.d(TAG, "[ExoPlayer:Discontinuity] reason=$reasonStr fromMs=${oldPosition.positionMs} toMs=${newPosition.positionMs}")
            }

            override fun onSurfaceSizeChanged(
                eventTime: AnalyticsListener.EventTime,
                width: Int,
                height: Int
            ) {
                Log.d(TAG, "[ExoPlayer:SurfaceSize] width=$width height=$height")
            }
        }
    }
}
