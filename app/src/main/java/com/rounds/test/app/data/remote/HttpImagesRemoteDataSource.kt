package com.rounds.test.app.data.remote

import com.rounds.test.app.data.remote.parser.ImageListJsonParser
import com.rounds.test.app.model.ImageItem
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches the image list with the platform [HttpURLConnection].
 *
 * One GET against one endpoint does not justify Retrofit or OkHttp. The image loader downloads
 * image bytes with its own downloader; that class is internal to `:imageloader` and answers a
 * different question, so this data source deliberately duplicates the small amount of connection
 * handling instead of coupling the two modules through their networking internals.
 *
 * The default [endpointUrl] is the production address; tests override it with a loopback server.
 */
internal class HttpImagesRemoteDataSource(
    private val endpointUrl: String = IMAGE_LIST_URL,
    private val parser: ImageListJsonParser = ImageListJsonParser(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) : ImagesRemoteDataSource {

    /**
     * Both the request and the parsing run on [ioDispatcher], so nothing here touches the main
     * thread. A non-2xx status or an empty body surfaces as [IOException]; the connection is always
     * disconnected and the stream always closed.
     */
    override suspend fun getImages(): List<ImageItem> = withContext(ioDispatcher) {
        parser.parse(fetchBody())
    }

    @Throws(IOException::class)
    private fun fetchBody(): String {
        val connection = URL(endpointUrl).openConnection() as? HttpURLConnection
            ?: throw IOException("Unsupported protocol for image list url: $endpointUrl")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doInput = true

            val responseCode = connection.responseCode
            if (responseCode !in HTTP_SUCCESS_CODES) {
                throw IOException("Unexpected HTTP status $responseCode for $endpointUrl")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) throw IOException("Empty image list response from $endpointUrl")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        private const val IMAGE_LIST_URL =
            "https://zipoapps-storage-test.nyc3.digitaloceanspaces.com/image_list.json"
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 15_000
        private val HTTP_SUCCESS_CODES = 200..299
    }
}
