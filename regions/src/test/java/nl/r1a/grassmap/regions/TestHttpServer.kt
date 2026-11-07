package nl.r1a.grassmap.regions

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * A loopback HTTP server for the download tests. It records every request's
 * Authorization header so a test can prove whether the client sent a token,
 * and it serves whatever handler the test installs.
 */
internal class TestHttpServer(
    handler: TestHttpServer.(HttpExchange) -> Unit,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName(HOST), 0), 0)
    private val requests = Collections.synchronizedList(mutableListOf<String?>())
    private val count = AtomicInteger()

    init {
        server.createContext("/") { exchange ->
            requests += exchange.requestHeaders.getFirst(AUTHORIZATION)
            count.incrementAndGet()
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    val baseUrl: String
        get() = "http://$HOST:${server.address.port}"

    val port: Int
        get() = server.address.port

    val requestCount: Int
        get() = count.get()

    /** Every Authorization value the server saw, in order. */
    fun authorizations(): List<String?> = requests.toList()

    fun send(
        exchange: HttpExchange,
        status: Int,
        body: ByteArray,
    ) {
        exchange.responseHeaders.set(CONTENT_TYPE, APPLICATION_ZIP)
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    fun redirect(
        exchange: HttpExchange,
        status: Int,
        location: String,
    ) {
        exchange.responseHeaders.set(LOCATION, location)
        exchange.sendResponseHeaders(status, -1)
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val AUTHORIZATION = "Authorization"
        const val CONTENT_TYPE = "Content-Type"
        const val APPLICATION_ZIP = "application/zip"
        const val LOCATION = "Location"
    }
}

/** A server that serves [body] only for the exact bearer [token]. */
internal fun authenticatedPackageServer(
    token: String,
    body: ByteArray,
): TestHttpServer =
    TestHttpServer { exchange ->
        val expected = "${BearerToken.SCHEME} $token"
        if (exchange.requestHeaders.getFirst(TestHttpServer.AUTHORIZATION) != expected) {
            send(exchange, 401, ByteArray(0))
        } else {
            send(exchange, 200, body)
        }
    }
