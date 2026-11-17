package nl.r1a.grassmap.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections

/**
 * A loopback server that answers like EOX's WMTS tile endpoint
 * (`.../default/g/{z}/{y}/{x}.jpg`). Each tile body names its own tile, so a
 * test can tell which tile it read back. It records every path it served.
 */
internal class EoxMockServer : AutoCloseable {
    private val seen = Collections.synchronizedList(mutableListOf<String>())
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName(HOST), 0), 0)

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    /** The EOX template shape, pointed at this server. */
    val template: String
        get() = "http://$HOST:${server.address.port}/wmts/1.0.0/s2cloudless-2018_3857/default/g/{z}/{y}/{x}.jpg"

    val requestCount: Int get() = seen.size

    fun paths(): List<String> = seen.toList()

    private fun handle(exchange: HttpExchange) {
        seen += exchange.requestURI.path
        try {
            val parts = exchange.requestURI.path.removeSuffix(".jpg").split('/')
            val body = tileBody(z = parts[parts.size - 3].toInt(), x = parts.last().toInt(), y = parts[parts.size - 2].toInt())
            exchange.responseHeaders.set("Content-Type", "image/jpeg")
            exchange.sendResponseHeaders(HTTP_OK, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        } finally {
            exchange.close()
        }
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        const val HOST = "127.0.0.1"
        private const val HTTP_OK = 200
        private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

        fun tileBody(
            z: Int,
            x: Int,
            y: Int,
        ): ByteArray = JPEG_SOI + "tile-$z-$x-$y".toByteArray()
    }
}
