package com.eclip.rup.leb.utils

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executor

/**
 * Dedicated Coroutine Dispatchers providing strict thread isolation across subsystems:
 * - [Thumbnail]: Limited parallelism worker pool (max 3) for MediaMetadataRetriever frame decoding.
 * - [Waveform]: Dedicated worker pool (max 2) for FFmpeg audio PCM extraction & amplitude calculation.
 * - [DiskIo]: Fast I/O pool (max 4) for WebP disk cache, project recipes, and draft serialization.
 * - [Compute]: CPU-bound pool for mathematical transforms, timeline bounds calculations, and diffing.
 * - [Main]: Android UI Main Thread exclusively for touch handling, view rendering, and GPU transforms.
 * - [Decoder]: Single-thread dispatcher backed by the dedicated MediaCodec HandlerThread.
 *   All MediaCodec/MediaExtractor operations MUST run on this dispatcher to guarantee
 *   zero codec work on the UI thread.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
object AppDispatchers {
    val Thumbnail: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(3)
    val Waveform: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)
    val DiskIo: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)
    val Compute: CoroutineDispatcher = Dispatchers.Default
    val Main: CoroutineDispatcher = Dispatchers.Main

    /**
     * Bounded dispatcher for video import pipeline (file copy, validation, waveform).
     * Lazily initialized after [DeviceCapabilityManager.initialize] has run,
     * so the parallelism limit reflects the actual device RAM tier.
     */
    val ImportWorker: CoroutineDispatcher by lazy {
        Dispatchers.IO.limitedParallelism(
            DeviceCapabilityManager.importConcurrency.coerceAtLeast(1)
        )
    }

    /**
     * Cached decoder dispatcher instance. Created once when the Looper is set.
     */
    @Volatile
    private var cachedDecoderDispatcher: CoroutineDispatcher? = null

    /**
     * Single-thread CoroutineDispatcher backed by the DecoderHandlerThread's Looper.
     * All MediaCodec decode and MediaExtractor seek work dispatches onto this thread,
     * keeping the Main thread 100% free from codec operations.
     *
     * Falls back to [Dispatchers.IO] with parallelism=1 if the decoder thread hasn't
     * been initialized yet (defensive guard against early access).
     */
    val Decoder: CoroutineDispatcher
        get() = cachedDecoderDispatcher ?: Dispatchers.IO.limitedParallelism(1)

    /**
     * Initializes the [Decoder] dispatcher with the provided [Looper] from the
     * [com.eclip.rup.leb.engine.decoder.DecoderHandlerThread].
     * Must be called once from Application.onCreate() after starting the decoder thread.
     *
     * Converts the Looper into an [Executor] that posts Runnables to the Handler,
     * then wraps it as a [CoroutineDispatcher] via [Executor.asCoroutineDispatcher].
     */
    fun initDecoderDispatcher(looper: Looper) {
        val handler = Handler(looper)
        val executor = Executor { runnable -> handler.post(runnable) }
        cachedDecoderDispatcher = executor.asCoroutineDispatcher()
    }
}

