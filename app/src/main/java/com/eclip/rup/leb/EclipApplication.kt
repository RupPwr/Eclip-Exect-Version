package com.eclip.rup.leb

import android.app.Application
import android.content.Intent
import android.util.Log
import com.eclip.rup.leb.engine.cache.PreviewFrameCache
import com.eclip.rup.leb.engine.decoder.DecoderCallback
import com.eclip.rup.leb.engine.decoder.DecoderHandlerThread
import com.eclip.rup.leb.utils.AppDispatchers
import com.eclip.rup.leb.utils.AudioWaveformExtractor
import com.eclip.rup.leb.utils.BitmapPool
import com.eclip.rup.leb.utils.DeviceCapabilityManager
import com.eclip.rup.leb.utils.ErrorCode
import com.eclip.rup.leb.utils.ThermalProtectionManager
import com.eclip.rup.leb.utils.ThumbnailCacheManager

class EclipApplication : Application() {

    /**
     * Pre-allocated emergency memory reserve (256KB).
     * Released at crash time to free heap space for the crash handler's
     * minimal allocations (Intent, small string extras).
     * Marked @Volatile so the null-write is immediately visible to all threads.
     */
    @Volatile
    private var emergencyMemoryReserve: ByteArray? = ByteArray(256 * 1024)

    /**
     * The dedicated background HandlerThread for all MediaCodec/MediaExtractor
     * operations. Initialized once at app startup; its Looper is wired into
     * [AppDispatchers.Decoder] so coroutines can dispatch onto it.
     */
    private var decoderThread: DecoderHandlerThread? = null

    override fun onCreate() {
        super.onCreate()
        DeviceCapabilityManager.initialize(this)
        setupGlobalCrashHandler()
        ThermalProtectionManager.initialize(this)
        initializeDecoderThread()
    }

    /**
     * Starts the dedicated decoder HandlerThread and wires its Looper into
     * [AppDispatchers.Decoder] so all MediaCodec work is strictly off the UI thread.
     */
    private fun initializeDecoderThread() {
        val noOpCallback = object : DecoderCallback {
            override fun onFrameDecoded(framePtsUs: Long, requestId: Long) {
                // Individual Activities/ViewModels will set their own callbacks
                // when they acquire the decoder thread. This no-op serves only
                // to satisfy the constructor requirement at init time.
            }

            override fun onDecoderError(error: Throwable, isFatal: Boolean) {
                Log.e("EclipApplication", "Decoder error (fatal=$isFatal): ${error.message}")
            }

            override fun onDecodeRangeComplete() {
                // No-op at application level
            }

            override fun onDecoderInitialized(codecName: String, isHardware: Boolean) {
                Log.i("EclipApplication", "Decoder initialized: codec=$codecName, hw=$isHardware")
            }
        }

        val thread = DecoderHandlerThread(this, noOpCallback)
        thread.start()
        decoderThread = thread

        // Wire the decoder thread's Looper into AppDispatchers so any coroutine
        // using AppDispatchers.Decoder runs on this dedicated thread.
        AppDispatchers.initDecoderDispatcher(thread.looper)

        Log.i("EclipApplication", "DecoderHandlerThread started and wired to AppDispatchers.Decoder")
    }

    private fun setupGlobalCrashHandler() {
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Step 1: Release emergency reserve to free ~256KB of heap for
            // the minimal allocations below (Intent, small string extras).
            emergencyMemoryReserve = null

            val isOom = throwable is OutOfMemoryError

            try {
                if (isOom) {
                    // Step 2: Aggressively clear all in-memory caches to maximize
                    // available heap before attempting Intent creation.
                    try { ThumbnailCacheManager.clearMemoryCache() } catch (_: Throwable) {}
                    try { PreviewFrameCache.clearMemoryCache() } catch (_: Throwable) {}
                    try { BitmapPool.clear() } catch (_: Throwable) {}
                    try { AudioWaveformExtractor.clearCache() } catch (_: Throwable) {}
                    try { com.eclip.rup.leb.utils.RAMFrameBufferManager.clearAll() } catch (_: Throwable) {}
                }

                // Step 3: Build a minimal Intent.
                // For OOM: avoid Log.getStackTraceString which allocates a large StringWriter.
                // For other crashes: cap trace at 4KB to prevent accidental huge allocations.
                val errorLog = if (isOom) {
                    "OutOfMemoryError (stack trace omitted to conserve memory)"
                } else {
                    try {
                        Log.getStackTraceString(throwable).take(4000)
                    } catch (_: Throwable) {
                        throwable.message ?: "Unknown error"
                    }
                }

                val errorCode = if (isOom) {
                    ErrorCode.OUT_OF_MEMORY.code
                } else {
                    ErrorCode.UNEXPECTED_CRASH.code
                }

                val intent = Intent(this, ErrorDisplayActivity::class.java).apply {
                    putExtra("ERROR_CODE", errorCode)
                    putExtra("ERROR_LOG", errorLog)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
            } catch (_: Throwable) {
                // Double-fault protection: if even the minimal path fails,
                // delegate to the system default handler.
                try {
                    oldHandler?.uncaughtException(thread, throwable)
                } catch (_: Throwable) {}
            }

            // Terminate the crashed process
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DeviceCapabilityManager.onTrimMemory(level)
        ThumbnailCacheManager.trimMemory(level)
        PreviewFrameCache.trimMemory(level)
        AudioWaveformExtractor.clearCache()
        
        when (level) {
            TRIM_MEMORY_RUNNING_LOW -> {
                com.eclip.rup.leb.utils.RAMFrameBufferManager.shrinkCapacity(20)
            }
            TRIM_MEMORY_UI_HIDDEN -> {
                com.eclip.rup.leb.utils.RAMFrameBufferManager.clearAll()
            }
            TRIM_MEMORY_RUNNING_CRITICAL, TRIM_MEMORY_COMPLETE -> {
                com.eclip.rup.leb.utils.RAMFrameBufferManager.clearAll()
                PreviewFrameCache.clearMemoryCache()
                // Timeline prefetch disable could be done globally or at activity level,
                // here we just clear the RAM buffer to save memory immediately.
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ThumbnailCacheManager.clearMemoryCache()
        PreviewFrameCache.clearMemoryCache()
        BitmapPool.clear()
        AudioWaveformExtractor.clearCache()
        com.eclip.rup.leb.utils.RAMFrameBufferManager.clearAll()
    }
}

