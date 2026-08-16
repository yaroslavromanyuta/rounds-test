package com.rounds.imageloader.network

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads images with the platform [HttpURLConnection]. Supports HTTP and HTTPS; no third-party
 * HTTP client is used.
 */
internal class HttpImageDownloader(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val temporaryDirectory: File? = null,
) : ImageDownloader {

    init {
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
        temporaryDirectory
            ?.takeIf(File::isDirectory)
            ?.listFiles { file -> file.isFile && file.name.startsWith(TEMPORARY_FILE_PREFIX) }
            ?.forEach(File::delete)
    }

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

            val declaredSize = connection.contentLengthLong
            if (declaredSize > maxResponseBytes) throw responseTooLarge(url)

            val bytes = connection.inputStream.buffered().use { input ->
                input.readBytesLimited(url, declaredSize)
            }
            if (bytes.isEmpty()) throw IOException("Empty image response for $url")
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Reads at most [maxResponseBytes], enforcing the limit even when `Content-Length` is absent or
     * false. The crossing chunk is rejected before it is copied into the output buffer.
     */
    private fun InputStream.readBytesLimited(url: String, declaredSize: Long): ByteArray {
        if (declaredSize in 0..maxResponseBytes.toLong()) {
            val expectedBytes = declaredSize.toInt()
            val exact = ByteArray(expectedBytes)
            var offset = 0
            while (offset < expectedBytes) {
                val readBytes = read(exact, offset, expectedBytes - offset)
                if (readBytes == -1) {
                    throw IOException("Image response for $url ended before its declared Content-Length")
                }
                if (readBytes > 0) offset += readBytes
            }
            // Correct fixed-length responses return here without a second full-size ByteArray copy.
            val nextByte = read()
            if (nextByte == -1) return exact
            throw IOException("Image response for $url exceeds its declared Content-Length")
        }

        return readUnknownLengthBytes(url)
    }

    private fun InputStream.readUnknownLengthBytes(url: String): ByteArray {
        val temporaryFile = createTemporaryFile()
        try {
            return RandomAccessFile(temporaryFile, "rw").use { spool ->
                var totalBytes = 0
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val readBytes = read(buffer)
                    if (readBytes == -1) break
                    if (readBytes == 0) continue
                    if (readBytes > maxResponseBytes - totalBytes) throw responseTooLarge(url)
                    spool.write(buffer, 0, readBytes)
                    totalBytes += readBytes
                }

                val bytes = ByteArray(totalBytes)
                spool.seek(0L)
                var offset = 0
                while (offset < bytes.size) {
                    val readBytes = spool.read(bytes, offset, bytes.size - offset)
                    if (readBytes == -1) throw IOException("Temporary image response was truncated")
                    if (readBytes > 0) offset += readBytes
                }
                bytes
            }
        } finally {
            // Android/Linux may unlink an active spool during cache clearing. The open descriptor
            // above remains valid through readback; after it closes, this also handles normal and
            // failed transfers. A failed delete remains recognisable for startup recovery.
            temporaryFile.delete()
        }
    }

    private fun createTemporaryFile(): File {
        temporaryDirectory?.let { directory ->
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Unable to create image response directory")
            }
        }
        return File.createTempFile(TEMPORARY_FILE_PREFIX, null, temporaryDirectory)
    }

    private fun responseTooLarge(url: String): IOException =
        IOException("Image response for $url exceeds $maxResponseBytes bytes")

    private companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 15_000
        private const val DEFAULT_MAX_RESPONSE_BYTES = 32 * 1024 * 1024
        private const val TEMPORARY_FILE_PREFIX = "image-loader-response-"
        private val HTTP_SUCCESS_CODES = 200..299
    }
}
