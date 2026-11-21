package nl.r1a.grassmaps.plugins.raster

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections

/** One scripted answer of [ScriptedTileServer]. */
internal data class Reply(
    val status: Int = TileServer.STATUS_OK,
    val body: ByteArray = TileServer.TILE_BYTES,
    val headers: Map<String, String> = emptyMap(),
    val contentType: String = "image/jpeg",
)

/**
 * A loopback server whose answer is chosen per request by [respond], which
 * gets the zero-based request number and the path. It records every path.
 */
internal class ScriptedTileServer(
    private val respond: (index: Int, path: String) -> Reply,
) : AutoCloseable {
    private val seen = Collections.synchronizedList(mutableListOf<String>())
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName(TileServer.HOST), 0), 0)

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    val port: Int get() = server.address.port

    val baseUrl: String get() = "http://${TileServer.HOST}:$port"

    fun paths(): List<String> = seen.toList()

    private fun handle(exchange: HttpExchange) {
        val reply =
            synchronized(seen) {
                val index = seen.size
                seen += exchange.requestURI.path
                respond(index, exchange.requestURI.path)
            }
        try {
            exchange.responseHeaders.set(TileServer.CONTENT_TYPE, reply.contentType)
            reply.headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
            exchange.sendResponseHeaders(reply.status, reply.body.size.toLong())
            exchange.responseBody.use { it.write(reply.body) }
        } finally {
            exchange.close()
        }
    }

    override fun close() {
        server.stop(0)
    }
}
