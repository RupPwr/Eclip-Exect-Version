package com.eclip.rup.leb.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/**
 * Thread-safe Singleton to manage a global pool of hardware MediaCodec permits.
 * Ensures we don't exceed device limits even across different pipeline stages.
 */
object CodecInstancePool {
    private const val MAX_POSSIBLE_PERMITS = 100
    private val semaphore = Semaphore(MAX_POSSIBLE_PERMITS)
    private val mutex = Mutex()

    private var targetConcurrency = 4
    private var effectiveConcurrency = 4

    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        // Initially consume dummy permits so only the requested concurrency is available.
        repeat(MAX_POSSIBLE_PERMITS - effectiveConcurrency) {
            // Safe to use tryAcquire here because we know they are available
            semaphore.tryAcquire()
        }
    }

    /**
     * Executes the given [block] holding a hardware codec permit.
     * Ensures strict limit and safe release on exception/cancellation.
     */
    suspend fun <T> withHardwareCodecPermit(block: suspend () -> T): T {
        semaphore.acquire()
        try {
            return block()
        } finally {
            semaphore.release()
        }
    }

    /**
     * Dynamically adjust the concurrency limit without dropping active tasks
     * and without recreating the Semaphore.
     */
    fun setMaxConcurrency(count: Int) {
        val newTarget = max(0, count).coerceAtMost(MAX_POSSIBLE_PERMITS)

        scope.launch {
            mutex.withLock {
                targetConcurrency = newTarget

                // 1. If we need to increase, do it immediately inside the lock
                while (effectiveConcurrency < targetConcurrency) {
                    semaphore.release()
                    effectiveConcurrency++
                }
            }

            // 2. If we need to decrease, we must acquire dummy permits. 
            // We do this outside the lock so we don't block subsequent setMaxConcurrency calls 
            // from scaling back up while waiting for tasks to finish.
            while (true) {
                val shouldAcquire = mutex.withLock {
                    if (effectiveConcurrency > targetConcurrency) {
                        effectiveConcurrency--
                        true
                    } else {
                        false
                    }
                }

                if (shouldAcquire) {
                    // This will safely suspend until a running codec operation releases its permit.
                    semaphore.acquire()
                } else {
                    break
                }
            }
        }
    }
}
