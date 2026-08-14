package com.rounds.imageloader.testing

import android.graphics.Bitmap
import com.rounds.imageloader.cache.Clock
import com.rounds.imageloader.cache.DiskImageCache
import com.rounds.imageloader.cache.ImageCache
import com.rounds.imageloader.cache.MemoryImageCache
import com.rounds.imageloader.decode.ImageDecoder
import com.rounds.imageloader.network.ImageDownloader
import com.rounds.imageloader.request.Target
import com.rounds.imageloader.request.TargetRequest
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Scripted downloader. Hand-written rather than mocked: the tests care about which URLs were
 * requested and in which order, which a fake states more directly than verification.
 */
internal class FakeImageDownloader : ImageDownloader {

    private val responses = mutableMapOf<String, Result<ByteArray>>()

    val requestedUrls = mutableListOf<String>()

    /** Invoked inside [download], so a test can act while a request is in flight. */
    var onDownload: ((String) -> Unit)? = null

    fun respondWith(url: String, bytes: ByteArray) {
        responses[url] = Result.success(bytes)
    }

    fun failWith(url: String, error: IOException) {
        responses[url] = Result.failure(error)
    }

    override fun download(url: String): ByteArray {
        requestedUrls += url
        onDownload?.invoke(url)
        val response = responses[url] ?: throw IOException("No response scripted for $url")
        return response.getOrThrow()
    }
}

/** Decoder that maps exact byte payloads to bitmaps; anything unmapped decodes to `null`. */
internal class FakeImageDecoder : ImageDecoder {

    private val bitmaps = mutableMapOf<String, Bitmap>()

    var decodeCount = 0
        private set

    fun decodeTo(bytes: ByteArray, bitmap: Bitmap) {
        bitmaps[bytes.decodeToString()] = bitmap
    }

    override fun decode(bytes: ByteArray): Bitmap? {
        decodeCount++
        return bitmaps[bytes.decodeToString()]
    }
}

/**
 * Movable time source. TTL behaviour is asserted by advancing this rather than by waiting, so the
 * four-hour boundary can be tested exactly and instantly.
 */
internal class FakeClock(var currentMillis: Long = 0L) : Clock {

    override fun nowMillis(): Long = currentMillis

    fun advanceBy(millis: Long) {
        currentMillis += millis
    }
}

/**
 * Builds a cache over a real (temporary) directory and the test dispatcher, so tests exercise the
 * production `MemoryImageCache`/`DiskImageCache` rather than substitutes of them.
 */
internal fun testImageCache(
    directory: File,
    clock: Clock,
    diskDispatcher: CoroutineDispatcher,
    memoryMaxSizeBytes: Int = DEFAULT_TEST_MEMORY_BYTES,
): ImageCache = ImageCache(
    memory = MemoryImageCache(clock, memoryMaxSizeBytes),
    disk = DiskImageCache(directory, clock),
    diskDispatcher = diskDispatcher,
)

private const val DEFAULT_TEST_MEMORY_BYTES = 8 * 1024 * 1024

/** Records what would have been applied to an `ImageView`. */
internal class FakeTarget : Target {

    var placeholderRes: Int? = null
        private set

    val appliedBitmaps = mutableListOf<Bitmap>()

    private var request: TargetRequest? = null

    override fun setPlaceholder(resId: Int) {
        placeholderRes = resId
    }

    override fun setBitmap(bitmap: Bitmap) {
        appliedBitmaps += bitmap
    }

    override fun currentRequest(): TargetRequest? = request

    override fun setCurrentRequest(request: TargetRequest?) {
        this.request = request
    }
}
