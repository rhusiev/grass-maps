package nl.r1a.grassmap.regions

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URI

class HttpDownloadTransportTest {
    private val transport = HttpDownloadTransport()
    private val body = "package-bytes".toByteArray()

    @Test
    fun cleartextIsRefusedUnlessThePolicyAllowsIt() =
        runTest {
            val outcome = fetch("http://127.0.0.1:1/package.zip", policy(allowCleartext = false))

            assertEquals(Outcome.Failure(AppError.Network), outcome.first)
        }

    @Test
    fun aMalformedOrUnsupportedUrlIsInvalidInput() =
        runTest {
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                fetch("ftp://example.test/package.zip", policy()).first,
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                fetch("http:///no-host", policy()).first,
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                fetch("http://user:pass@127.0.0.1/package.zip", policy()).first,
            )
        }

    @Test
    fun aSameHostRedirectIsFollowed() =
        runTest {
            TestHttpServer { exchange ->
                if (exchange.requestURI.path == "/redirect") {
                    redirect(exchange, 302, "/package.zip")
                } else {
                    send(exchange, 200, body)
                }
            }.use { server ->
                val outcome = fetch("${server.baseUrl}/redirect", policy())

                assertEquals(Outcome.Success(TransportResult(200)), outcome.first)
                assertTrue(outcome.second.contentEquals(body))
            }
        }

    @Test
    fun aCrossHostRedirectIsRefusedUnlessAllowed() =
        runTest {
            TestHttpServer { exchange -> send(exchange, 200, body) }.use { target ->
                val localhost = target.baseUrl.replace(TestHttpServer.HOST, "localhost")
                TestHttpServer { exchange -> redirect(exchange, 302, "$localhost/package.zip") }.use { redirector ->
                    val refused = fetch("${redirector.baseUrl}/package.zip", policy())
                    assertEquals(Outcome.Failure(AppError.Network), refused.first)
                    assertEquals(0, target.requestCount)

                    val allowed =
                        fetch(
                            "${redirector.baseUrl}/package.zip",
                            policy(allowCrossHost = true),
                        )
                    assertEquals(Outcome.Success(TransportResult(200)), allowed.first)
                    assertTrue(allowed.second.contentEquals(body))
                    assertTrue(redirector.authorizations().firstOrNull() != null)
                    assertNull("a cross-origin redirect must drop the token", target.authorizations().lastOrNull())
                }
            }
        }

    @Test
    fun aSchemeChangeIsADifferentOrigin() {
        assertFalse(
            transport.sameAuthority(
                URI("https://example.test:80/a"),
                URI("http://example.test:80/b"),
            ),
        )
        assertTrue(
            transport.sameAuthority(
                URI("https://example.test:443/a"),
                URI("https://example.test/b"),
            ),
        )
    }

    @Test
    fun aRedirectToAnotherPortOnTheSameHostIsRefused() =
        runTest {
            TestHttpServer { exchange -> send(exchange, 200, body) }.use { target ->
                TestHttpServer { exchange ->
                    redirect(exchange, 302, "http://${TestHttpServer.HOST}:${target.port}/package.zip")
                }.use { redirector ->
                    val refused = fetch("${redirector.baseUrl}/package.zip", policy())

                    assertEquals(Outcome.Failure(AppError.Network), refused.first)
                    assertEquals(0, target.requestCount)
                }
            }
        }

    @Test
    fun aRefusedStatusDoesNotReachTheSink() =
        runTest {
            TestHttpServer { exchange -> send(exchange, 401, "denied".toByteArray()) }.use { server ->
                val received = ByteArrayOutputStream()
                val result =
                    transport.get(request("${server.baseUrl}/package.zip", policy())) { buffer, length ->
                        received.write(buffer, 0, length)
                        Outcome.Success(Unit)
                    }

                assertEquals(Outcome.Success(TransportResult(401)), result)
                assertEquals(0, received.size())
            }
        }

    @Test
    fun aCancelledSinkStopsTheTransfer() =
        runTest {
            TestHttpServer { exchange -> send(exchange, 200, body) }.use { server ->
                val result =
                    transport.get(request("${server.baseUrl}/package.zip", policy())) { _, _ ->
                        Outcome.Failure(AppError.Cancelled)
                    }

                assertEquals(Outcome.Failure(AppError.Cancelled), result)
            }
        }

    @Test
    fun aBodyOverThePackageLimitIsRejected() =
        runTest {
            TestHttpServer { exchange -> send(exchange, 200, body) }.use { server ->
                val outcome = fetch("${server.baseUrl}/package.zip", policy().copy(maxPackageBytes = 4))

                assertEquals(Outcome.Failure(AppError.CorruptData), outcome.first)
            }
        }

    @Test
    fun aPlain200ReportsItsContentLengthAsTheArchiveTotal() =
        runTest {
            TestHttpServer { exchange -> send(exchange, 200, body) }.use { server ->
                val begun = beginsOf("${server.baseUrl}/package.zip", rangeStart = 0)

                assertEquals(listOf(0L to body.size.toLong()), begun)
            }
        }

    @Test
    fun aResumed206ReportsTheContentRangeTotalNotTheRemainingLength() =
        runTest {
            val start = 5L
            TestHttpServer { exchange ->
                val rest = body.copyOfRange(start.toInt(), body.size)
                exchange.responseHeaders.set("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                send(exchange, 206, rest)
            }.use { server ->
                val begun = beginsOf("${server.baseUrl}/package.zip", rangeStart = start)

                assertEquals(listOf(start to body.size.toLong()), begun)
            }
        }

    @Test
    fun aResponseWithNoLengthHeaderReportsNoTotal() =
        runTest {
            TestHttpServer { exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(body) }
            }.use { server ->
                val begun = beginsOf("${server.baseUrl}/package.zip", rangeStart = 0)

                assertEquals(listOf<Pair<Long, Long?>>(0L to null), begun)
            }
        }

    @Test
    fun aRangeWithAnUnknownTotalIsRejectedBeforeTheSinkBegins() = assertRangeRejected("bytes 5-${body.size - 1}/*")

    @Test
    fun aMalformedContentRangeIsRejectedBeforeTheSinkBegins() = assertRangeRejected("items 5-9/10")

    @Test
    fun aRangeThatStartsElsewhereThanAskedIsRejectedBeforeTheSinkBegins() = assertRangeRejected("bytes 3-${body.size - 1}/${body.size}")

    @Test
    fun aRangeThatStopsBeforeTheEndOfTheFileIsRejectedBeforeTheSinkBegins() =
        assertRangeRejected("bytes 5-${body.size - 2}/${body.size}", rest = body.copyOfRange(5, body.size - 1))

    @Test
    fun aContentLengthThatDisagreesWithTheRangeIsRejectedBeforeTheSinkBegins() =
        runTest {
            TestHttpServer { exchange ->
                exchange.responseHeaders.set("Content-Range", "bytes 5-${body.size - 1}/${body.size}")
                exchange.sendResponseHeaders(206, (body.size - 6).toLong())
                exchange.responseBody.use { it.write(body, 5, body.size - 6) }
            }.use { server ->
                val (outcome, begun) = attempt("${server.baseUrl}/package.zip", rangeStart = 5)

                assertEquals(Outcome.Success(TransportResult(206, rangeRejected = true)), outcome)
                assertTrue(begun.isEmpty())
            }
        }

    @Test
    fun aBodyShorterThanItsContentLengthFailsAsANetworkError() =
        runTest {
            TestHttpServer { exchange ->
                exchange.sendResponseHeaders(200, (body.size + 10).toLong())
                exchange.responseBody.use { it.write(body) }
            }.use { server ->
                val (outcome, _) = attempt("${server.baseUrl}/package.zip", rangeStart = 0)

                assertEquals(Outcome.Failure(AppError.Network), outcome)
            }
        }

    private fun assertRangeRejected(
        contentRange: String,
        rest: ByteArray = body.copyOfRange(5, body.size),
    ) = runTest {
        TestHttpServer { exchange ->
            exchange.responseHeaders.set("Content-Range", contentRange)
            send(exchange, 206, rest)
        }.use { server ->
            val (outcome, begun) = attempt("${server.baseUrl}/package.zip", rangeStart = 5)

            assertEquals(Outcome.Success(TransportResult(206, rangeRejected = true)), outcome)
            assertTrue(begun.isEmpty())
        }
    }

    private suspend fun beginsOf(
        url: String,
        rangeStart: Long,
    ): List<Pair<Long, Long?>> {
        val (outcome, begun) = attempt(url, rangeStart)
        assertTrue(outcome is Outcome.Success)
        return begun
    }

    private suspend fun attempt(
        url: String,
        rangeStart: Long,
    ): Pair<Outcome<TransportResult>, List<Pair<Long, Long?>>> {
        val begun = mutableListOf<Pair<Long, Long?>>()
        val sink =
            object : BodySink {
                override suspend fun begin(
                    offset: Long,
                    totalBytes: Long?,
                ): Outcome<Unit> {
                    begun += offset to totalBytes
                    return Outcome.Success(Unit)
                }

                override suspend fun write(
                    buffer: ByteArray,
                    length: Int,
                ): Outcome<Unit> = Outcome.Success(Unit)
            }
        return transport.get(request(url, policy()).copy(rangeStart = rangeStart), sink) to begun
    }

    private suspend fun fetch(
        url: String,
        policy: DownloadPolicy,
    ): Pair<Outcome<TransportResult>, ByteArray> {
        val received = ByteArrayOutputStream()
        val result =
            transport.get(request(url, policy)) { buffer, length ->
                received.write(buffer, 0, length)
                Outcome.Success(Unit)
            }
        return result to received.toByteArray()
    }

    private fun request(
        url: String,
        policy: DownloadPolicy,
    ): DownloadRequest =
        DownloadRequest(
            url = url,
            token = (BearerToken.of("test-token-0123456789abcdef") as Outcome.Success).value,
            policy = policy,
        )

    private fun policy(
        allowCleartext: Boolean = true,
        allowCrossHost: Boolean = false,
    ): DownloadPolicy =
        DownloadPolicy(
            switch = SWITCH,
            connectTimeoutMs = 2_000,
            readTimeoutMs = 5_000,
            allowCleartextHttp = allowCleartext,
            allowCrossHostRedirect = allowCrossHost,
        )

    private companion object {
        val SWITCH: SwitchId = SwitchId("network.region_updates")
    }
}
