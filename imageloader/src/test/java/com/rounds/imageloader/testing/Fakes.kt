package com.rounds.imageloader.testing

import android.graphics.Bitmap
import com.rounds.imageloader.decode.ImageDecoder
import com.rounds.imageloader.network.ImageDownloader
import com.rounds.imageloader.request.Target
import com.rounds.imageloader.request.TargetRequest
import java.io.IOException

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
