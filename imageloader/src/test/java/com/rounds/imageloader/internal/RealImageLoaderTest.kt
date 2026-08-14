package com.rounds.imageloader.internal

import android.graphics.Bitmap
import com.rounds.imageloader.ImageLoader
import com.rounds.imageloader.request.TargetRequest
import com.rounds.imageloader.testing.FakeClock
import com.rounds.imageloader.testing.FakeImageDecoder
import com.rounds.imageloader.testing.FakeImageDownloader
import com.rounds.imageloader.testing.FakeTarget
import com.rounds.imageloader.testing.testImageCache
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

/**
 * Covers the load pipeline through the internal [com.rounds.imageloader.request.Target] seam, which
 * keeps the tests free of Robolectric and of any timing-sensitive waiting: both dispatchers are the
 * same [StandardTestDispatcher], so execution order is fully controlled by the test.
 *
 * `Bitmap` cannot be instantiated on the JVM, so it is the one collaborator that is mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealImageLoaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val downloader = FakeImageDownloader()
    private val decoder = FakeImageDecoder()
    private val target = FakeTarget()
    private val clock = FakeClock()

    // An empty cache over a fresh temporary directory: every load here exercises the network path.
    private val loader by lazy {
        RealImageLoader(
            cache = testImageCache(temporaryFolder.root, clock, dispatcher),
            downloader = downloader,
            decoder = decoder,
            clock = clock,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )
    }

    @Test
    fun `placeholder is applied before any download starts`() = runTest(dispatcher) {
        scriptSuccess(URL_A, BYTES_A, bitmapA)

        loader.load(target, URL_A, PLACEHOLDER)

        assertEquals(PLACEHOLDER, target.placeholderRes)
        assertTrue("download must not run synchronously", downloader.requestedUrls.isEmpty())

        advanceUntilIdle()
    }

    @Test
    fun `successful load delivers the decoded bitmap to the target`() = runTest(dispatcher) {
        scriptSuccess(URL_A, BYTES_A, bitmapA)

        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(listOf(URL_A), downloader.requestedUrls)
        assertEquals(1, decoder.decodeCount)
        assertEquals(listOf<Bitmap>(bitmapA), target.appliedBitmaps)
    }

    @Test
    fun `download failure leaves the placeholder and does not crash`() = runTest(dispatcher) {
        downloader.failWith(URL_A, IOException("offline"))

        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(PLACEHOLDER, target.placeholderRes)
        assertTrue(target.appliedBitmaps.isEmpty())
    }

    @Test
    fun `decode failure leaves the placeholder and does not crash`() = runTest(dispatcher) {
        downloader.respondWith(URL_A, BYTES_A)
        // No mapping registered, so the decoder returns null for these bytes.

        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(1, decoder.decodeCount)
        assertEquals(PLACEHOLDER, target.placeholderRes)
        assertTrue(target.appliedBitmaps.isEmpty())
    }

    @Test
    fun `loading without a placeholder empties the target and still delivers the image`() =
        runTest(dispatcher) {
            scriptSuccess(URL_A, BYTES_A, bitmapA)

            loader.load(target, URL_A, ImageLoader.NO_PLACEHOLDER)

            // The target must not keep a previous item's image while the new one loads.
            assertEquals(ImageLoader.NO_PLACEHOLDER, target.placeholderRes)

            advanceUntilIdle()

            assertEquals(listOf<Bitmap>(bitmapA), target.appliedBitmaps)
        }

    @Test
    fun `blank url shows the placeholder and starts no request`() = runTest(dispatcher) {
        loader.load(target, "  ", PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(PLACEHOLDER, target.placeholderRes)
        assertTrue(downloader.requestedUrls.isEmpty())
        assertTrue(target.appliedBitmaps.isEmpty())
    }

    @Test
    fun `a new request for the same target cancels the previous one`() = runTest(dispatcher) {
        scriptSuccess(URL_A, BYTES_A, bitmapA)
        scriptSuccess(URL_B, BYTES_B, bitmapB)

        loader.load(target, URL_A, PLACEHOLDER)
        val firstJob = requireNotNull(target.currentRequest()).job
        loader.load(target, URL_B, PLACEHOLDER)
        advanceUntilIdle()

        assertTrue(firstJob.isCancelled)
        assertEquals(listOf<Bitmap>(bitmapB), target.appliedBitmaps)
    }

    @Test
    fun `an obsolete result cannot overwrite a newer request`() = runTest(dispatcher) {
        scriptSuccess(URL_A, BYTES_A, bitmapA)

        loader.load(target, URL_A, PLACEHOLDER)
        // The target is claimed by a newer request while A is in flight, without cancelling A:
        // the completion guard alone has to discard A's result, whatever the completion order.
        target.setCurrentRequest(TargetRequest(token = Long.MAX_VALUE, job = Job()))
        advanceUntilIdle()

        assertEquals(listOf(URL_A), downloader.requestedUrls)
        assertTrue(target.appliedBitmaps.isEmpty())
    }

    @Test
    fun `clear before the request runs prevents delivery and cancels the work`() =
        runTest(dispatcher) {
            scriptSuccess(URL_A, BYTES_A, bitmapA)

            loader.load(target, URL_A, PLACEHOLDER)
            val job = requireNotNull(target.currentRequest()).job
            loader.clear(target)
            advanceUntilIdle()

            assertTrue("cancellation must not be swallowed", job.isCancelled)
            assertTrue(downloader.requestedUrls.isEmpty())
            assertTrue(target.appliedBitmaps.isEmpty())
        }

    @Test
    fun `clear while the request is in flight prevents delivery`() = runTest(dispatcher) {
        scriptSuccess(URL_A, BYTES_A, bitmapA)
        downloader.onDownload = { loader.clear(target) }

        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(listOf(URL_A), downloader.requestedUrls)
        assertTrue(target.appliedBitmaps.isEmpty())
    }

    @Test
    fun `a failing request does not affect another target`() = runTest(dispatcher) {
        val otherTarget = FakeTarget()
        downloader.failWith(URL_A, IOException("offline"))
        scriptSuccess(URL_B, BYTES_B, bitmapB)

        loader.load(target, URL_A, PLACEHOLDER)
        loader.load(otherTarget, URL_B, PLACEHOLDER)
        advanceUntilIdle()

        assertTrue(target.appliedBitmaps.isEmpty())
        assertEquals(listOf<Bitmap>(bitmapB), otherTarget.appliedBitmaps)
    }

    @Test
    fun `a completed request releases its slot on the target`() = runTest(dispatcher) {
        scriptSuccess(URL_A, BYTES_A, bitmapA)

        loader.load(target, URL_A, PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(null, target.currentRequest())
    }

    private fun scriptSuccess(url: String, bytes: ByteArray, bitmap: Bitmap) {
        downloader.respondWith(url, bytes)
        decoder.decodeTo(bytes, bitmap)
    }

    private companion object {
        private const val PLACEHOLDER = 4242
        private const val URL_A = "https://example.test/a.png"
        private const val URL_B = "https://example.test/b.png"
        private val BYTES_A = "image-a".toByteArray()
        private val BYTES_B = "image-b".toByteArray()
        private val bitmapA: Bitmap = mock(Bitmap::class.java)
        private val bitmapB: Bitmap = mock(Bitmap::class.java)
    }
}
