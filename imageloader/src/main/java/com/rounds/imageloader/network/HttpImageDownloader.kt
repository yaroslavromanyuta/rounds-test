package com.rounds.imageloader.network

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads images with the platform [HttpURLConnection]. Supports HTTP and HTTPS; no third-party
 * HTTP client is used.
 */
internal class HttpImageDownloader(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) : ImageDownloader {

    /**
     * A malformed URL surfaces as [java.net.MalformedURLException], a non-2xx status and an empty
     * body as [IOException]. The connection is always disconnected and the stream always closed.
     */
    @Throws(IOException::class)
    override fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as? HttpURLConnection
            ?: throw IOException("Unsupported protocol for image url: $url")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doInput = true

            val responseCode = connection.responseCode
            if (responseCode !in HTTP_SUCCESS_CODES) {
                throw IOException("Unexpected HTTP status $responseCode for $url")
            }

            val bytes = connection.inputStream.buffered().use { it.readBytes() }
            if (bytes.isEmpty()) throw IOException("Empty image response for $url")
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 15_000
        private val HTTP_SUCCESS_CODES = 200..299
    }
}
