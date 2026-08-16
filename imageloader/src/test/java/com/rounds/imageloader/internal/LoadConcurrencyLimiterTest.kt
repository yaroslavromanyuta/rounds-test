package com.rounds.imageloader.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoadConcurrencyLimiterTest {

    @Test
    fun `runs no more than the configured number of loads at once`() = runTest {
        val limiter = SemaphoreLoadLimiter(maxConcurrentLoads = 2)
        val release = CompletableDeferred<Unit>()
        var activeLoads = 0
        var highestActiveLoads = 0

        val loads = List(3) {
            launch {
                limiter.run {
                    activeLoads++
                    highestActiveLoads = maxOf(highestActiveLoads, activeLoads)
                    try {
                        release.await()
                    } finally {
                        activeLoads--
                    }
                }
            }
        }

        runCurrent()

        assertEquals(2, activeLoads)
        assertEquals(2, highestActiveLoads)

        release.complete(Unit)
        loads.joinAll()

        assertEquals(0, activeLoads)
        assertEquals(2, highestActiveLoads)
    }

    @Test
    fun `cancellation releases the permit for the next load`() = runTest {
        val limiter = SemaphoreLoadLimiter(maxConcurrentLoads = 1)
        val entered = CompletableDeferred<Unit>()
        val cancelled = launch {
            limiter.run {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()

        cancelled.cancelAndJoin()

        var nextLoadRan = false
        limiter.run { nextLoadRan = true }
        assertTrue(nextLoadRan)
    }
}
