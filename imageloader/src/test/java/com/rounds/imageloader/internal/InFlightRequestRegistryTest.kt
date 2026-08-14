package com.rounds.imageloader.internal

import android.graphics.Bitmap
import com.rounds.imageloader.cache.CacheSnapshot
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * The registry on its own, away from the load pipeline.
 *
 * Cleanup and ownership are the properties that are awkward to observe through `RealImageLoader` —
 * "the entry was released" looks identical to "the cache answered" from the outside — so they are
 * asserted directly here. Behaviour that *is* visible through the pipeline is tested there instead,
 * in [RealImageLoaderConcurrencyTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InFlightRequestRegistryTest {

    private val dispatcher = StandardTestDispatcher()

    /**
     * Stands in for the loader's own scope. Deliberately not the test's scope: shared work must
     * never be a child of the coroutine that happened to ask for it.
     */
    private val loaderScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Test
    fun `concurrent requests for the same key share one operation`() = runTest(dispatcher) {
        val registry = InFlightRequestRegistry(loaderScope)
        var producerCalls = 0

        val first = registry.sharedLoad(KEY_A) { producerCalls++; bitmapA }
        val second = registry.sharedLoad(KEY_A) { producerCalls++; bitmapB }

        assertSame("the second caller must join, not create", first, second)
        assertEquals(bitmapA, first.await())
        assertEquals(bitmapA, second.await())
        assertEquals(1, producerCalls)
    }

    @Test
    fun `different keys get independent operations`() = runTest(dispatcher) {
        val registry = InFlightRequestRegistry(loaderScope)

        val forA = registry.sharedLoad(KEY_A) { bitmapA }
        val forB = registry.sharedLoad(KEY_B) { bitmapB }

        // Both are registered at once: deduplication must not serialise unrelated images.
        assertEquals(2, registry.inFlightCount())
        assertEquals(bitmapA, forA.await())
        assertEquals(bitmapB, forB.await())
    }

    @Test
    fun `the same url in a different cache generation is a different operation`() =
        runTest(dispatcher) {
            val registry = InFlightRequestRegistry(loaderScope)

            val old = registry.sharedLoad(KEY_A) { bitmapA }
            val new = registry.sharedLoad(KEY_A_INVALIDATED) { bitmapB }

            assertEquals(bitmapA, old.await())
            assertEquals(bitmapB, new.await())
        }

    @Test
    fun `a successful operation is released`() = runTest(dispatcher) {
        val registry = InFlightRequestRegistry(loaderScope)

        registry.sharedLoad(KEY_A) { bitmapA }.await()
        advanceUntilIdle()

        assertEquals(0, registry.inFlightCount())
    }

    @Test
    fun `a failed operation is released and does not poison the key`() = runTest(dispatcher) {
        val registry = InFlightRequestRegistry(loaderScope)

        val failed = registry.sharedLoad(KEY_A) { throw IOException("offline") }

        assertEquals("a failure must reach consumers as a miss, not a throw", null, failed.await())
        advanceUntilIdle()
        assertEquals(0, registry.inFlightCount())

        // The key is reusable: a later request starts fresh work rather than replaying the failure.
        val retried = registry.sharedLoad(KEY_A) { bitmapA }
        assertEquals(bitmapA, retried.await())
    }

    @Test
    fun `a cancelled operation is released`() = runTest(dispatcher) {
        val registry = InFlightRequestRegistry(loaderScope)
        val blocked = CompletableDeferred<Bitmap?>()

        val shared = registry.sharedLoad(KEY_A) { blocked.await() }
        advanceUntilIdle()
        assertEquals(1, registry.inFlightCount())

        shared.cancel()
        advanceUntilIdle()

        assertEquals(0, registry.inFlightCount())
    }

    @Test
    fun `cancelling one consumer leaves the shared operation and the others intact`() =
        runTest(dispatcher) {
            val registry = InFlightRequestRegistry(loaderScope)
            val blocked = CompletableDeferred<Bitmap?>()
            val shared = registry.sharedLoad(KEY_A) { blocked.await() }

            var abandoned: Bitmap? = null
            var received: Bitmap? = null
            val giveUp = launch { abandoned = shared.await() }
            val waitOn = launch { received = shared.await() }
            advanceUntilIdle()

            giveUp.cancel()
            advanceUntilIdle()

            assertTrue("one consumer leaving must not end the shared work", shared.isActive)

            blocked.complete(bitmapA)
            advanceUntilIdle()

            assertEquals(null, abandoned)
            assertSame(bitmapA, received)
            assertTrue(waitOn.isCompleted)
            assertFalse(waitOn.isCancelled)
        }

    private companion object {
        private val KEY_A = InFlightKey("https://example.test/a.png", CacheSnapshot(0L, 0L))
        private val KEY_B = InFlightKey("https://example.test/b.png", CacheSnapshot(0L, 0L))
        private val KEY_A_INVALIDATED =
            InFlightKey("https://example.test/a.png", CacheSnapshot(0L, 1L))
        private val bitmapA: Bitmap = mock(Bitmap::class.java)
        private val bitmapB: Bitmap = mock(Bitmap::class.java)
    }
}
