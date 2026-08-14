package com.rounds.imageloader.network

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real `HttpURLConnection` implementation against a JDK HTTP server bound to an
 * ephemeral loopback port. Deterministic and offline — no production endpoint and no public image
 * host are involved.
 */
class HttpImageDownloaderTest {

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
}
