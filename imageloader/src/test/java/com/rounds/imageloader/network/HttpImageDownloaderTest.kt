package com.rounds.imageloader.network

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the real `HttpURLConnection` implementation against a JDK HTTP server bound to an
 * ephemeral loopback port. Deterministic and offline — no production endpoint and no public image
 * host are involved.
 */
class HttpImageDownloaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val downloader = HttpImageDownloader()
    private lateinit var server: HttpServer

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `returns the response body`() {
        val body = "not really a png, but the downloader does not decode".toByteArray()
        serve("/image", status = 200, body = body)

        assertArrayEquals(body, downloader.download(url("/image")))
    }

    @Test
    fun `returns a chunked response and removes its temporary file`() {
        val body = byteArrayOf(1, 2, 3, 4)
        serveChunked("/chunked-image", status = 200, body = body)
        val temporaryDownloader = HttpImageDownloader(temporaryDirectory = temporaryFolder.root)

        assertArrayEquals(body, temporaryDownloader.download(url("/chunked-image")))
        assertTrue(temporaryFolder.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun `initialization removes an orphaned response temporary file`() {
        val orphan = temporaryFolder.newFile("image-loader-response-orphan.tmp")

        HttpImageDownloader(temporaryDirectory = temporaryFolder.root)

        assertTrue(!orphan.exists())
    }

    @Test
    fun `cache clear cannot break an active chunked response spool`() {
        assertActiveSpoolSurvives { directory ->
            directory.listFiles()?.forEach { file -> file.delete() }
        }
    }

    @Test
    fun `another downloader initialization cannot break an active chunked response spool`() {
        assertActiveSpoolSurvives { directory ->
            HttpImageDownloader(temporaryDirectory = directory)
        }
    }

    @Test
    fun `rejects a non-success status`() {
        serve("/missing", status = 404, body = ByteArray(0))

        assertThrows(IOException::class.java) { downloader.download(url("/missing")) }
    }

    @Test
    fun `rejects an empty body`() {
        serve("/empty", status = 200, body = ByteArray(0))

        assertThrows(IOException::class.java) { downloader.download(url("/empty")) }
    }

    @Test
    fun `accepts a response exactly at the encoded size limit`() {
        val body = byteArrayOf(1, 2, 3, 4)
        serve("/exact-limit", status = 200, body = body)
        val bounded = HttpImageDownloader(maxResponseBytes = body.size)

        assertArrayEquals(body, bounded.download(url("/exact-limit")))
    }

    @Test
    fun `rejects a declared response larger than the encoded size limit`() {
        serve("/declared-too-large", status = 200, body = byteArrayOf(1, 2, 3, 4, 5))
        val bounded = HttpImageDownloader(maxResponseBytes = 4)

        val failure = assertThrows(IOException::class.java) {
            bounded.download(url("/declared-too-large"))
        }

        assertTrue(failure.message.orEmpty().contains("exceeds 4 bytes"))
    }

    @Test
    fun `rejects a chunked response that crosses the encoded size limit`() {
        serveChunked("/chunked-too-large", status = 200, body = byteArrayOf(1, 2, 3, 4, 5))
        val bounded = HttpImageDownloader(maxResponseBytes = 4)

        val failure = assertThrows(IOException::class.java) {
            bounded.download(url("/chunked-too-large"))
        }

        assertTrue(failure.message.orEmpty().contains("exceeds 4 bytes"))
    }

    @Test
    fun `production downloader rejects a declared response above 32 MiB`() {
        serveDeclaredLength("/default-declared-too-large", DEFAULT_MAX_RESPONSE_BYTES + 1L)

        val failure = assertThrows(IOException::class.java) {
            downloader.download(url("/default-declared-too-large"))
        }

        assertTrue(failure.message.orEmpty().contains("exceeds $DEFAULT_MAX_RESPONSE_BYTES bytes"))
    }

    @Test
    fun `rejects a response shorter than its declared content length`() {
        serveDeclaredLength("/truncated", bodyBytes = 5L)

        assertThrows(IOException::class.java) {
            downloader.download(url("/truncated"))
        }
    }

    @Test
    fun `production downloader rejects a chunked response above 32 MiB`() {
        serveChunkedBytes("/default-chunked-too-large", DEFAULT_MAX_RESPONSE_BYTES + 1)
        val productionDownloader = HttpImageDownloader(temporaryDirectory = temporaryFolder.root)

        val failure = assertThrows(IOException::class.java) {
            productionDownloader.download(url("/default-chunked-too-large"))
        }

        assertTrue(failure.message.orEmpty().contains("exceeds $DEFAULT_MAX_RESPONSE_BYTES bytes"))
        assertTrue(temporaryFolder.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun `rejects a malformed url`() {
        assertThrows(IOException::class.java) { downloader.download("not a url") }
    }

    @Test
    fun `rejects a non-http url`() {
        assertThrows(IOException::class.java) { downloader.download("file:///tmp/image.png") }
    }

    private fun url(path: String): String = "http://${server.address.hostString}:${server.address.port}$path"

    private fun serve(path: String, status: Int, body: ByteArray) {
        server.createContext(path) { exchange ->
            // -1 declares "no response body"; a positive length declares its exact size.
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1L else body.size.toLong())
            exchange.responseBody.use { output -> if (body.isNotEmpty()) output.write(body) }
        }
    }

    private fun serveChunked(path: String, status: Int, body: ByteArray) {
        server.createContext(path) { exchange ->
            // Zero asks HttpServer to use chunked transfer encoding, so no trustworthy length is
            // available before the downloader starts reading.
            exchange.sendResponseHeaders(status, 0L)
            exchange.responseBody.use { output -> output.write(body) }
        }
    }

    private fun assertActiveSpoolSurvives(deleteWhileActive: (File) -> Unit) {
        val body = ByteArray(DEFAULT_BUFFER_SIZE * 2) { index -> index.toByte() }
        val firstChunkSent = CountDownLatch(1)
        val continueResponse = CountDownLatch(1)
        server.createContext("/paused-chunked") { exchange ->
            exchange.sendResponseHeaders(200, 0L)
            exchange.responseBody.use { output ->
                output.write(body, 0, DEFAULT_BUFFER_SIZE)
                output.flush()
                firstChunkSent.countDown()
                check(continueResponse.await(5, TimeUnit.SECONDS))
                output.write(body, DEFAULT_BUFFER_SIZE, body.size - DEFAULT_BUFFER_SIZE)
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val activeDownloader = HttpImageDownloader(temporaryDirectory = temporaryFolder.root)
            val result = executor.submit<ByteArray> {
                activeDownloader.download(url("/paused-chunked"))
            }
            assertTrue(firstChunkSent.await(5, TimeUnit.SECONDS))
            assertTrue(waitForTemporarySpool())

            deleteWhileActive(temporaryFolder.root)
            continueResponse.countDown()

            assertArrayEquals(body, result.get(5, TimeUnit.SECONDS))
            assertTrue(temporaryFolder.root.listFiles().isNullOrEmpty())
        } finally {
            continueResponse.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun waitForTemporarySpool(): Boolean {
        repeat(100) {
            if (
                temporaryFolder.root.listFiles()?.any { file ->
                    file.name.startsWith("image-loader-response-")
                } == true
            ) {
                return true
            }
            Thread.sleep(10)
        }
        return false
    }

    private fun serveDeclaredLength(path: String, bodyBytes: Long) {
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, bodyBytes)
            exchange.responseBody.use { output ->
                // Flush one byte so the fixed-length headers reach the client. The downloader must
                // reject from Content-Length before it attempts to consume the incomplete body.
                output.write(0)
                output.flush()
            }
        }
    }

    private fun serveChunkedBytes(path: String, bodyBytes: Int) {
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, 0L)
            exchange.responseBody.use { output ->
                val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                var remainingBytes = bodyBytes
                while (remainingBytes > 0) {
                    val writeBytes = minOf(chunk.size, remainingBytes)
                    output.write(chunk, 0, writeBytes)
                    remainingBytes -= writeBytes
                }
            }
        }
    }

    private companion object {
        private const val DEFAULT_MAX_RESPONSE_BYTES = 32 * 1024 * 1024
    }
}
