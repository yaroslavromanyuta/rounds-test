package com.rounds.imageloader.internal

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Executes image-producing work under a process-local concurrency bound. */
internal interface LoadLimiter {
    suspend fun <T> run(block: suspend () -> T): T
}

/**
 * Limits transient memory and I/O pressure from distinct cache-miss pipelines.
 *
 * [Semaphore.withPermit] releases the permit on success, failure, and coroutine cancellation.
 */
internal class SemaphoreLoadLimiter(maxConcurrentLoads: Int) : LoadLimiter {

    init {
        require(maxConcurrentLoads > 0) { "maxConcurrentLoads must be positive" }
    }

    private val semaphore = Semaphore(maxConcurrentLoads)

    override suspend fun <T> run(block: suspend () -> T): T = semaphore.withPermit {
        block()
    }
}
