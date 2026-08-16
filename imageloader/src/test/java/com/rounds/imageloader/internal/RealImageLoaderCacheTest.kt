package com.rounds.imageloader.internal

import android.graphics.Bitmap
import com.rounds.imageloader.cache.CACHE_TTL_MILLIS
import com.rounds.imageloader.cache.DiskImageCache
import com.rounds.imageloader.cache.ImageCache
import com.rounds.imageloader.cache.MemoryImageCache
import com.rounds.imageloader.request.TargetRequest
import com.rounds.imageloader.testing.FakeClock
import com.rounds.imageloader.testing.FakeImageDecoder
import com.rounds.imageloader.testing.FakeImageDownloader
import com.rounds.imageloader.testing.FakeTarget
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

/**
 * Cache integration for the load pipeline: tier ordering, what gets stored, and how invalidation
 * behaves against a request that is already running.
 *
 * The real `MemoryImageCache` and `DiskImageCache` are used — the disk one over a temporary
 * directory — so these tests cover the production cache code rather than a substitute of it. Time
 * moves through [FakeClock], never through waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealImageLoaderCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val downloader = FakeImageDownloader()
    private val decoder = FakeImageDecoder()
    private val target = FakeTarget()
    private val clock = FakeClock(START_MILLIS)

    private val memory by lazy { MemoryImageCache(clock, MEMORY_BYTES) }
    private val disk by lazy { DiskImageCache(temporaryFolder.root, clock) }
    private val loader by lazy { loaderWith(ImageCache(memory, disk, dispatcher)) }

    @Test
    fun `a valid memory entry is served without touching disk or network`() = runTest(dispatcher) {
        memory.put(URL_A, bitmapA, START_MILLIS)

        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(listOf<Bitmap>(bitmapA), target.appliedBitmaps)
        assertTrue(downloader.requestedUrls.isEmpty())
        assertEquals("no disk decode should have happened", 0, decoder.decodeCount)
    }

    @Test
    fun `a valid disk entry is decoded and served without the network`() = runTest(dispatcher) {
        disk.write(URL_A, BYTES_A, START_MILLIS)
        decoder.decodeTo(BYTES_A, bitmapA)
        clock.advanceBy(ONE_HOUR_MILLIS)

        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(listOf<Bitmap>(bitmapA), target.appliedBitmaps)
        assertTrue(downloader.requestedUrls.isEmpty())
        assertSame(bitmapA, memory.get(URL_A)?.bitmap)
    }

    @Test
    fun `promoting a disk entry into memory keeps the original timestamp`() = runTest(dispatcher) {
        disk.write(URL_A, BYTES_A, START_MILLIS)
        decoder.decodeTo(BYTES_A, bitmapA)
        clock.advanceBy(3 * ONE_HOUR_MILLIS)

        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(START_MILLIS, memory.get(URL_A)?.cachedAtMillis)
        // Four hours after the original download the promoted entry is gone - promotion must not
        // restart the window.
        clock.currentMillis = START_MILLIS + CACHE_TTL_MILLIS
        assertNull(memory.get(URL_A))
    }

    @Test
    fun `a complete miss downloads, decodes and fills both cache tiers`() = runTest(dispatcher) {
        downloader.respondWith(URL_A, BYTES_A)
        decoder.decodeTo(BYTES_A, bitmapA)

        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(listOf(URL_A), downloader.requestedUrls)
        assertEquals(listOf<Bitmap>(bitmapA), target.appliedBitmaps)
        assertSame(bitmapA, memory.get(URL_A)?.bitmap)
        assertEquals(START_MILLIS, memory.get(URL_A)?.cachedAtMillis)
        assertArrayEquals(BYTES_A, disk.read(URL_A)?.bytes)
        assertEquals(START_MILLIS, disk.read(URL_A)?.cachedAtMillis)
    }

    @Test
    fun `expired entries in both tiers cause a fresh download`() = runTest(dispatcher) {
        memory.put(URL_A, bitmapB, START_MILLIS)
        disk.write(URL_A, BYTES_B, START_MILLIS)
        clock.advanceBy(CACHE_TTL_MILLIS)
        downloader.respondWith(URL_A, BYTES_A)
        decoder.decodeTo(BYTES_A, bitmapA)

        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(listOf(URL_A), downloader.requestedUrls)
        assertEquals(listOf<Bitmap>(bitmapA), target.appliedBitmaps)
    }

    @Test
    fun `a disk entry that cannot be decoded falls through to the network`() = runTest(dispatcher) {
        // Stored bytes with no decoder mapping - what a corrupt cache file looks like downstream.
        disk.write(URL_A, BYTES_B, START_MILLIS)
        downloader.respondWith(URL_A, BYTES_A)
        decoder.decodeTo(BYTES_A, bitmapA)

        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(listOf(URL_A), downloader.requestedUrls)
        assertEquals(listOf<Bitmap>(bitmapA), target.appliedBitmaps)
    }

    @Test
    fun `stale corrupt cleanup cannot delete a post-invalidation replacement`() =
        runTest(dispatcher) {
            val cache = ImageCache(memory, disk, dispatcher)
            disk.write(URL_A, BYTES_B, START_MILLIS)
            val staleSnapshot = cache.snapshot(URL_A)
            val staleEntry = requireNotNull(cache.readFromDisk(URL_A))
            assertArrayEquals(BYTES_B, staleEntry.bytes)

            // Models invalidation and a fresh download completing while the old corrupt decode was
            // suspended. Its later cleanup still carries the pre-invalidation snapshot.
            cache.invalidate(URL_A)
            cache.invalidateOnDisk(URL_A)
            val freshSnapshot = cache.snapshot(URL_A)
            cache.putOnDisk(URL_A, BYTES_A, START_MILLIS, freshSnapshot)

            cache.dropDiskEntry(URL_A, staleEntry, staleSnapshot)

            assertArrayEquals(BYTES_A, disk.read(URL_A)?.bytes)
        }

    @Test
    fun `delayed corrupt cleanup cannot delete a same-generation replacement`() =
        runTest(dispatcher) {
            val cache = ImageCache(memory, disk, dispatcher)
            disk.write(URL_A, BYTES_B, START_MILLIS)
            val snapshot = cache.snapshot(URL_A)
            val staleEntry = requireNotNull(cache.readFromDisk(URL_A))

            // Another request removes the corrupt entry and writes a valid response without any
            // invalidation, so the generation remains unchanged.
            disk.remove(URL_A)
            cache.putOnDisk(URL_A, BYTES_A, START_MILLIS, snapshot)

            cache.dropDiskEntry(URL_A, staleEntry, snapshot)

            assertArrayEquals(BYTES_A, disk.read(URL_A)?.bytes)
        }

    @Test
    fun `a failed download caches nothing`() = runTest(dispatcher) {
        downloader.failWith(URL_A, IOException("offline"))

        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertNull(memory.get(URL_A))
        assertNull(disk.read(URL_A))
    }

    @Test
    fun `a failed decode caches nothing`() = runTest(dispatcher) {
        downloader.respondWith(URL_A, BYTES_A)
        // No decoder mapping registered, so decoding returns null.

        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertNull(memory.get(URL_A))
        assertNull(disk.read(URL_A))
    }

    @Test
    fun `a disk write failure still delivers the image`() = runTest(dispatcher) {
        // A plain file where the cache directory should be: every disk write fails.
        val blocked = temporaryFolder.newFile("blocked")
        val brokenLoader = loaderWith(ImageCache(memory, DiskImageCache(blocked, clock), dispatcher))
        downloader.respondWith(URL_A, BYTES_A)
        decoder.decodeTo(BYTES_A, bitmapA)

        brokenLoader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(listOf<Bitmap>(bitmapA), target.appliedBitmaps)
        assertSame(bitmapA, memory.get(URL_A)?.bitmap)
    }

    @Test
    fun `a cache hit still respects the target's current request`() = runTest(dispatcher) {
        memory.put(URL_A, bitmapA, START_MILLIS)

        loader.load(target, URL_A, PLACEHOLDER)
        // The target is claimed by a newer request before the cached result is applied.
        target.setCurrentRequest(TargetRequest(token = Long.MAX_VALUE, job = Job()))
        advanceUntilIdle()

        assertTrue(target.appliedBitmaps.isEmpty())
    }

    @Test
    fun `clearCache empties both tiers and the next load downloads again`() = runTest(dispatcher) {
        downloader.respondWith(URL_A, BYTES_A)
        decoder.decodeTo(BYTES_A, bitmapA)
        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        loader.clearCache()
        advanceUntilIdle()

        assertNull(memory.get(URL_A))
        assertNull(disk.read(URL_A))
        assertEquals(0, temporaryFolder.root.listFiles()?.size)

        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(listOf(URL_A, URL_A), downloader.requestedUrls)
    }

    @Test
    fun `invalidate removes only the requested url`() = runTest(dispatcher) {
        downloader.respondWith(URL_A, BYTES_A)
        downloader.respondWith(URL_B, BYTES_B)
        decoder.decodeTo(BYTES_A, bitmapA)
        decoder.decodeTo(BYTES_B, bitmapB)
        loader.load(target, URL_A, PLACEHOLDER)
        loader.load(FakeTarget(), URL_B, PLACEHOLDER)
        advanceUntilIdle()

        loader.invalidate(URL_A)
        advanceUntilIdle()

        assertNull(memory.get(URL_A))
        assertNull(disk.read(URL_A))
        assertNotNull(memory.get(URL_B))
        assertNotNull(disk.read(URL_B))
    }

    @Test
    fun `a load in flight when the cache is cleared cannot repopulate it`() = runTest(dispatcher) {
        downloader.respondWith(URL_A, BYTES_A)
        decoder.decodeTo(BYTES_A, bitmapA)
        downloader.onDownload = { loader.clearCache() }

        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        // The image the user asked for is still shown; it just does not resurrect the cache.
        assertEquals(listOf<Bitmap>(bitmapA), target.appliedBitmaps)
        assertNull(memory.get(URL_A))
        assertNull(disk.read(URL_A))
    }

    @Test
    fun `a load in flight when its url is invalidated cannot repopulate it`() = runTest(dispatcher) {
        downloader.respondWith(URL_A, BYTES_A)
        decoder.decodeTo(BYTES_A, bitmapA)
        downloader.onDownload = { loader.invalidate(URL_A) }

        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(listOf<Bitmap>(bitmapA), target.appliedBitmaps)
        assertNull(memory.get(URL_A))
        assertNull(disk.read(URL_A))
    }

    private fun loaderWith(cache: ImageCache) = RealImageLoader(
        cache = cache,
        downloader = downloader,
        decoder = decoder,
        clock = clock,
        ioDispatcher = dispatcher,
        mainDispatcher = dispatcher,
    )

    private companion object {
        private const val PLACEHOLDER = 4242
        private const val START_MILLIS = 1_000_000L
        private const val ONE_HOUR_MILLIS = 60L * 60L * 1000L
        private const val MEMORY_BYTES = 8 * 1024 * 1024
        private const val URL_A = "https://example.test/a.png"
        private const val URL_B = "https://example.test/b.png"
        private val BYTES_A = "image-a".toByteArray()
        private val BYTES_B = "image-b".toByteArray()
        private val bitmapA: Bitmap = mock(Bitmap::class.java)
        private val bitmapB: Bitmap = mock(Bitmap::class.java)
    }
}
