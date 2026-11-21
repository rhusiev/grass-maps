package nl.r1a.grassmaps.plugins.raster

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections

/**
 * A loopback tile server for the raster tests. It records every path it sees,
 * so a test can prove which tile URL the client built, and serves one small
 * image body with the content type the client checks.
 */
internal class TileServer(
    private val status: Int = STATUS_OK,
    private val contentType: String = PNG,
) : AutoCloseable {
    private val seen = Collections.synchronizedList(mutableListOf<String>())
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName(HOST), 0), 0)

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    val baseUrl: String
        get() = "http://$HOST:${server.address.port}"

    fun paths(): List<String> = seen.toList()

    private fun handle(exchange: HttpExchange) {
        seen += exchange.requestURI.path
        try {
            exchange.responseHeaders.set(CONTENT_TYPE, contentType)
            exchange.sendResponseHeaders(status, TILE_BYTES.size.toLong())
            exchange.responseBody.use { it.write(TILE_BYTES) }
        } finally {
            exchange.close()
        }
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val STATUS_OK = 200
        const val CONTENT_TYPE = "Content-Type"
        const val PNG = "image/png"
        val TILE_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
