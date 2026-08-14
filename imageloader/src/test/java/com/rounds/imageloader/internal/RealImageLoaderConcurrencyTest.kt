package com.rounds.imageloader.internal

import android.graphics.Bitmap
import com.rounds.imageloader.cache.DiskImageCache
import com.rounds.imageloader.cache.ImageCache
import com.rounds.imageloader.cache.MemoryImageCache
import com.rounds.imageloader.testing.FakeClock
import com.rounds.imageloader.testing.FakeImageDecoder
import com.rounds.imageloader.testing.FakeImageDownloader
import com.rounds.imageloader.testing.FakeTarget
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

/**
 * Concurrent loads through the full pipeline: what gets shared, what stays independent, and what a
 * cancellation is allowed to affect.
 *
 * Determinism comes from the same single [StandardTestDispatcher] used everywhere else — main, IO
 * and disk are one scheduler, so "while the request is in flight" is an exact point in the test
 * rather than a race. `FakeImageDownloader.onDownload` is the hook for acting at that point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealImageLoaderConcurrencyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val downloader = FakeImageDownloader()
    private val decoder = FakeImageDecoder()
    private val clock = FakeClock(START_MILLIS)

    private val memory by lazy { MemoryImageCache(clock, MEMORY_BYTES) }
    private val disk by lazy { DiskImageCache(temporaryFolder.root, clock) }
    private val loader by lazy {
        RealImageLoader(
            cache = ImageCache(memory, disk, dispatcher),
            downloader = downloader,
            decoder = decoder,
            clock = clock,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )
    }

    @Test
    fun `concurrent loads of the same url download and decode once`() = runTest(dispatcher) {
        scriptSuccess(URL_A, BYTES_A, bitmapA)
        val targets = List(3) { FakeTarget() }

        targets.forEach { loader.load(it, URL_A, PLACEHOLDER) }
        advanceUntilIdle()

        assertEquals(listOf(URL_A), downloader.requestedUrls)
        assertEquals(1, decoder.decodeCount)
        targets.forEach { assertEquals(listOf<Bitmap>(bitmapA), it.appliedBitmaps) }
    }

    @Test
    fun `a shared result is stored once in each cache tier`() = runTest(dispatcher) {
        scriptSuccess(URL_A, BYTES_A, bitmapA)

        repeat(3) { loader.load(FakeTarget(), URL_A, PLACEHOLDER) }
        advanceUntilIdle()

        assertSame(bitmapA, memory.get(URL_A)?.bitmap)
        assertEquals(START_MILLIS, memory.get(URL_A)?.cachedAtMillis)
        assertArrayEquals(BYTES_A, disk.read(URL_A)?.bytes)
        // Three consumers, one cache entry and no stray temporary files. That the write itself
        // happened once follows from the single download above - it is the producer that stores.
        assertEquals(1, temporaryFolder.root.listFiles()?.size)
    }

    @Test
    fun `different urls load independently`() = runTest(dispatcher) {
        scriptSuccess(URL_A, BYTES_A, bitmapA)
        scriptSuccess(URL_B, BYTES_B, bitmapB)
        val targetA = FakeTarget()
        val targetB = FakeTarget()

        loader.load(targetA, URL_A, PLACEHOLDER)
        loader.load(targetB, URL_B, PLACEHOLDER)
        advanceUntilIdle()

        // Deduplication is per key: unrelated images must not be collapsed or serialised.
        assertEquals(setOf(URL_A, URL_B), downloader.requestedUrls.toSet())
        assertEquals(2, downloader.requestedUrls.size)
        assertEquals(listOf<Bitmap>(bitmapA), targetA.appliedBitmaps)
        assertEquals(listOf<Bitmap>(bitmapB), targetB.appliedBitmaps)
    }

    @Test
    fun `clearing one consumer does not cancel work the others need`() = runTest(dispatcher) {
        scriptSuccess(URL_A, BYTES_A, bitmapA)
        val cleared = FakeTarget()
        val first = FakeTarget()
        val second = FakeTarget()
        loader.load(cleared, URL_A, PLACEHOLDER)
        loader.load(first, URL_A, PLACEHOLDER)
        loader.load(second, URL_A, PLACEHOLDER)

        // Mid-flight: all three are waiting on the one shared download.
        downloader.onDownload = { loader.clear(cleared) }
        advanceUntilIdle()

        assertEquals(listOf(URL_A), downloader.requestedUrls)
        assertTrue("a cleared target must receive nothing", cleared.appliedBitmaps.isEmpty())
        assertEquals(listOf<Bitmap>(bitmapA), first.appliedBitmaps)
        assertEquals(listOf<Bitmap>(bitmapA), second.appliedBitmaps)
    }

    @Test
    fun `a shared result cannot overwrite a reused target's newer image`() = runTest(dispatcher) {
        scriptSuccess(URL_A, BYTES_A, bitmapA)
        scriptSuccess(URL_B, BYTES_B, bitmapB)
        val reused = FakeTarget()
        val other = FakeTarget()

        loader.load(reused, URL_A, PLACEHOLDER)
        loader.load(other, URL_A, PLACEHOLDER)
        // The first target is recycled onto another image while A is still in flight.
        loader.load(reused, URL_B, PLACEHOLDER)
        advanceUntilIdle()

        assertEquals("the recycled target must show only B", listOf<Bitmap>(bitmapB), reused.appliedBitmaps)
        assertEquals(listOf<Bitmap>(bitmapA), other.appliedBitmaps)
    }

    @Test
    fun `a failed shared load is not replayed to the next request`() = runTest(dispatcher) {
        downloader.failWith(URL_A, IOException("offline"))
        val firstTargets = List(2) { FakeTarget() }

        firstTargets.forEach { loader.load(it, URL_A, PLACEHOLDER) }
        advanceUntilIdle()

        assertEquals(listOf(URL_A), downloader.requestedUrls)
        firstTargets.forEach { assertTrue(it.appliedBitmaps.isEmpty()) }

        // The failed entry must have been released, so a retry really retries.
        scriptSuccess(URL_A, BYTES_A, bitmapA)
        val retryTarget = FakeTarget()
        loader.load(retryTarget, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(listOf(URL_A, URL_A), downloader.requestedUrls)
        assertEquals(listOf<Bitmap>(bitmapA), retryTarget.appliedBitmaps)
    }

    @Test
    fun `a completed shared load leaves the cache to serve the next request`() =
        runTest(dispatcher) {
            scriptSuccess(URL_A, BYTES_A, bitmapA)

            loader.load(FakeTarget(), URL_A, PLACEHOLDER)
            advanceUntilIdle()

            val later = FakeTarget()
            loader.load(later, URL_A, PLACEHOLDER)
            advanceUntilIdle()

            // A retained entry would have been awaited instead; a released one falls back on the
            // memory cache, which is the whole point of releasing it.
            assertEquals(listOf(URL_A), downloader.requestedUrls)
            assertEquals(1, decoder.decodeCount)
            assertEquals(listOf<Bitmap>(bitmapA), later.appliedBitmaps)
        }

    @Test
    fun `a load started after invalidate does not join the pre-invalidation work`() =
        runTest(dispatcher) {
            val beforeInvalidation = FakeTarget()
            val afterInvalidation = FakeTarget()
            downloader.respondWith(URL_A, BYTES_A)
            decoder.decodeTo(BYTES_A, bitmapA)
            downloader.onDownload = onFirstDownload {
                loader.invalidate(URL_A)
                loader.load(afterInvalidation, URL_A, PLACEHOLDER)
            }

            loader.load(beforeInvalidation, URL_A, PLACEHOLDER)
            advanceUntilIdle()

            // Two downloads is the correct outcome here, not a deduplication failure: joining would
            // have served the new request from work the invalidation was meant to discard.
            assertEquals(listOf(URL_A, URL_A), downloader.requestedUrls)
            assertEquals(listOf<Bitmap>(bitmapA), beforeInvalidation.appliedBitmaps)
            assertEquals(listOf<Bitmap>(bitmapA), afterInvalidation.appliedBitmaps)
            // Only the post-invalidation generation may write; the old one is refused.
            assertSame(bitmapA, memory.get(URL_A)?.bitmap)
            assertArrayEquals(BYTES_A, disk.read(URL_A)?.bytes)
        }

    @Test
    fun `an invalidated load that has no successor cannot repopulate the cache`() =
        runTest(dispatcher) {
            scriptSuccess(URL_A, BYTES_A, bitmapA)
            val first = FakeTarget()
            val joined = FakeTarget()
            loader.load(first, URL_A, PLACEHOLDER)
            loader.load(joined, URL_A, PLACEHOLDER)
            downloader.onDownload = { loader.invalidate(URL_A) }

            advanceUntilIdle()

            // Both consumers still see their image - only the cache write is refused.
            assertEquals(listOf(URL_A), downloader.requestedUrls)
            assertEquals(listOf<Bitmap>(bitmapA), first.appliedBitmaps)
            assertEquals(listOf<Bitmap>(bitmapA), joined.appliedBitmaps)
            assertNull(memory.get(URL_A))
            assertNull(disk.read(URL_A))
        }

    @Test
    fun `a load started after clearCache does not join the pre-clear work`() = runTest(dispatcher) {
        val beforeClear = FakeTarget()
        val afterClear = FakeTarget()
        downloader.respondWith(URL_A, BYTES_A)
        decoder.decodeTo(BYTES_A, bitmapA)
        downloader.onDownload = onFirstDownload {
            loader.clearCache()
            loader.load(afterClear, URL_A, PLACEHOLDER)
        }

        loader.load(beforeClear, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        // The global generation must be honoured as well as the per-url one.
        assertEquals(listOf(URL_A, URL_A), downloader.requestedUrls)
        assertEquals(listOf<Bitmap>(bitmapA), beforeClear.appliedBitmaps)
        assertEquals(listOf<Bitmap>(bitmapA), afterClear.appliedBitmaps)
        assertSame(bitmapA, memory.get(URL_A)?.bitmap)
    }

    /** Runs [action] inside the first download only, so a nested load cannot recurse forever. */
    private fun onFirstDownload(action: () -> Unit): (String) -> Unit {
        var fired = false
        return {
            if (!fired) {
                fired = true
                action()
            }
        }
    }

    private fun scriptSuccess(url: String, bytes: ByteArray, bitmap: Bitmap) {
        downloader.respondWith(url, bytes)
        decoder.decodeTo(bytes, bitmap)
    }

    private companion object {
        private const val PLACEHOLDER = 4242
        private const val START_MILLIS = 1_000_000L
        private const val MEMORY_BYTES = 8 * 1024 * 1024
        private const val URL_A = "https://example.test/a.png"
        private const val URL_B = "https://example.test/b.png"
        private val BYTES_A = "image-a".toByteArray()
        private val BYTES_B = "image-b".toByteArray()
        private val bitmapA: Bitmap = mock(Bitmap::class.java)
        private val bitmapB: Bitmap = mock(Bitmap::class.java)
    }
}
