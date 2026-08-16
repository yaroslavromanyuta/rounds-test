package com.rounds.test.app.data.remote

import com.rounds.test.app.data.remote.parser.ImageListParseException
import com.rounds.test.app.model.ImageItem
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real `HttpURLConnection` request against a JDK HTTP server bound to an ephemeral
 * loopback port — the same pattern the image loader's downloader tests use. Deterministic and
 * offline: the production endpoint is never contacted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpImagesRemoteDataSourceTest {

    private val dispatcher = StandardTestDispatcher()
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
    fun `returns the records in response order`() = runTest(dispatcher) {
        serve(
            "/images",
            body = """
                [
                  { "id": 7, "imageUrl": "https://example.test/seven.jpg" },
                  { "id": 0, "imageUrl": "https://example.test/zero.jpg" }
                ]
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                ImageItem(7, "https://example.test/seven.jpg"),
                ImageItem(0, "https://example.test/zero.jpg"),
            ),
            dataSourceFor("/images").getImages(),
        )
    }

    @Test
    fun `returns an empty list when the endpoint has no records`() = runTest(dispatcher) {
        serve("/empty-list", body = "[]")

        assertEquals(emptyList<ImageItem>(), dataSourceFor("/empty-list").getImages())
    }

    @Test
    fun `issues a GET`() = runTest(dispatcher) {
        var method: String? = null
        serve("/images", body = "[]") { method = it }

        dataSourceFor("/images").getImages()

        assertEquals("GET", method)
    }

    @Test
    fun `fails on a malformed body`() = runTest(dispatcher) {
        serve("/broken", body = "{ not json")

        assertTrue(failureFrom("/broken") is ImageListParseException)
    }

    @Test
    fun `fails on an empty body`() = runTest(dispatcher) {
        serve("/blank", body = "")

        assertTrue(failureFrom("/blank") is IOException)
    }

    @Test
    fun `fails on a not found status`() = runTest(dispatcher) {
        serve("/missing", status = 404, body = "nope")

        assertTrue(failureFrom("/missing") is IOException)
    }

    @Test
    fun `fails on a server error status`() = runTest(dispatcher) {
        serve("/broken-server", status = 500, body = "boom")

        assertTrue(failureFrom("/broken-server") is IOException)
    }

    @Test
    fun `fails when the endpoint cannot be reached`() = runTest(dispatcher) {
        // A loopback port that was bound and immediately released: nothing is listening on it.
        val closedPort = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val unreachable = "http://${InetAddress.getLoopbackAddress().hostAddress}:$closedPort/images"

        val failure = runCatching { dataSource(unreachable).getImages() }.exceptionOrNull()

        assertTrue(failure is IOException)
    }

    private suspend fun failureFrom(path: String): Throwable? =
        runCatching { dataSourceFor(path).getImages() }.exceptionOrNull()

    private fun dataSourceFor(path: String): HttpImagesRemoteDataSource =
        dataSource("http://${server.address.hostString}:${server.address.port}$path")

    private fun dataSource(url: String): HttpImagesRemoteDataSource =
        HttpImagesRemoteDataSource(endpointUrl = url, ioDispatcher = dispatcher)

    private fun serve(
        path: String,
        status: Int = 200,
        body: String,
        onRequest: (String) -> Unit = {},
    ) {
        val bytes = body.toByteArray()
        server.createContext(path) { exchange ->
            onRequest(exchange.requestMethod)
            // -1 declares "no response body"; a positive length declares its exact size.
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
            exchange.responseBody.use { output -> if (bytes.isNotEmpty()) output.write(bytes) }
        }
    }
}
