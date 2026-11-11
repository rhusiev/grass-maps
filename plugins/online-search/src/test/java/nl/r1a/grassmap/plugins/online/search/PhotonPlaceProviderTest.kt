package nl.r1a.grassmap.plugins.online.search

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import nl.r1a.grassmap.plugin.api.service.NetworkGate
import nl.r1a.grassmap.plugin.api.service.NetworkSwitches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** The Photon provider: the switch, the URL shape, the retry and the cap. */
class PhotonPlaceProviderTest {
    @Test
    fun aRefusingGateNeverReachesTheTransport() =
        runTest {
            val gate = RefusingGate()
            val transport = RecordingTransport()
            val provider = provider(gate = gate, transport = transport)

            val outcome = provider.search("Львів", SearchContext(), limit = 5, cancellation = CancellationHandle.None)

            assertEquals(Outcome.Failure(AppError.SwitchOff(NetworkSwitches.onlineSearch)), outcome)
            assertEquals(0, transport.calls)
        }

    @Test
    fun theQueryAndLimitTravelInTheUrl() =
        runTest {
            val transport = RecordingTransport(SUCCESS)
            val provider = provider(transport = transport)

            val outcome = provider.search("Львів", SearchContext(), limit = 5, cancellation = CancellationHandle.None)

            assertTrue(outcome is Outcome.Success)
            assertEquals(1, transport.calls)
            val url = transport.urls.single()
            assertTrue(url, url.startsWith(PhotonPlaceProvider.DEFAULT_BASE_URL))
            assertEquals("Львів", queryValue(url, "q"))
            assertEquals("5", queryValue(url, "limit"))
            assertFalse(url, url.contains("lat="))
            assertFalse(url, url.contains("lon="))
        }

    @Test
    fun thePositionIsSentOnlyWhenTheContextCarriesIt() =
        runTest {
            val transport = RecordingTransport(SUCCESS)
            val provider = provider(transport = transport)

            provider.search(
                query = "Львів",
                context = SearchContext(position = GeoPoint(49.8419, 24.0316)),
                limit = 5,
                cancellation = CancellationHandle.None,
            )

            val url = transport.urls.single()
            assertEquals("49.8419", queryValue(url, "lat"))
            assertEquals("24.0316", queryValue(url, "lon"))
        }

    @Test
    fun theLanguageIsForwardedWhenTheCallerNamesOne() =
        runTest {
            val transport = RecordingTransport(SUCCESS)
            val provider = provider(transport = transport)

            provider.search("Lviv", SearchContext(language = "uk"), limit = 5, cancellation = CancellationHandle.None)

            assertEquals("uk", queryValue(transport.urls.single(), "lang"))
        }

    @Test
    fun aFeatureOutsideTheViewportIsFilteredOut() =
        runTest {
            val transport = RecordingTransport(TWO_FEATURES)
            val provider = provider(transport = transport)

            val outcome =
                provider.search(
                    query = "place",
                    context =
                        SearchContext(
                            viewport =
                                BoundingBox(
                                    southWest = GeoPoint(49.84, 24.03),
                                    northEast = GeoPoint(49.85, 24.04),
                                ),
                        ),
                    limit = 5,
                    cancellation = CancellationHandle.None,
                )

            assertEquals(listOf("Inside"), (outcome as Outcome.Success).value.map { it.name })
        }

    @Test
    fun oneFailedAttemptIsRetriedOnce() =
        runTest {
            val transport = RecordingTransport(Outcome.Failure(AppError.Network), SUCCESS)
            val pauses = mutableListOf<Long>()
            val provider = provider(transport = transport, pause = { millis -> pauses += millis })

            val outcome = provider.search("lviv", SearchContext(), limit = 5, cancellation = CancellationHandle.None)

            assertTrue(outcome is Outcome.Success)
            assertEquals(2, transport.calls)
            assertEquals(listOf(PhotonPlaceProvider.RETRY_DELAY_MILLIS), pauses)
        }

    @Test
    fun theRetryIsBoundedAtTwoAttempts() =
        runTest {
            val transport = RecordingTransport(Outcome.Failure(AppError.Network), Outcome.Failure(AppError.Network))
            val provider = provider(transport = transport)

            val outcome = provider.search("lviv", SearchContext(), limit = 5, cancellation = CancellationHandle.None)

            assertEquals(Outcome.Failure(AppError.Network), outcome)
            assertEquals(2, transport.calls)
        }

    @Test
    fun aRefusalIsNotRetried() =
        runTest {
            val transport = RecordingTransport(Outcome.Failure(AppError.Server(403)))
            val provider = provider(transport = transport)

            val outcome = provider.search("lviv", SearchContext(), limit = 5, cancellation = CancellationHandle.None)

            assertEquals(Outcome.Failure(AppError.Server(403)), outcome)
            assertEquals(1, transport.calls)
        }

    @Test
    fun aRetryAfterHeaderCannotStallPastTheCap() =
        runTest {
            val transport = RecordingTransport(Outcome.Failure(AppError.Server(429, retryAfter = 30)), SUCCESS)
            val pauses = mutableListOf<Long>()
            val provider = provider(transport = transport, pause = { millis -> pauses += millis })

            provider.search("lviv", SearchContext(), limit = 5, cancellation = CancellationHandle.None)

            assertEquals(listOf(PhotonPlaceProvider.MAX_RETRY_DELAY_MILLIS), pauses)
        }

    @Test
    fun twoRequestsStartAtLeastOneSecondApart() =
        runTest {
            val transport = RecordingTransport(SUCCESS, SUCCESS)
            val pauses = mutableListOf<Long>()
            val provider =
                provider(
                    transport = transport,
                    minIntervalMillis = PhotonPlaceProvider.MIN_INTERVAL_MILLIS,
                    pause = { millis -> pauses += millis },
                )

            provider.search("lviv", SearchContext(), limit = 5, cancellation = CancellationHandle.None)
            provider.search("lviv", SearchContext(), limit = 5, cancellation = CancellationHandle.None)

            assertTrue(pauses.contains(PhotonPlaceProvider.MIN_INTERVAL_MILLIS))
            assertEquals(2, transport.calls)
        }

    @Test
    fun anAlreadyCancelledCallOpensNothing() =
        runTest {
            val transport = RecordingTransport(SUCCESS)
            val provider = provider(transport = transport)

            val outcome =
                provider.search(
                    query = "lviv",
                    context = SearchContext(),
                    limit = 5,
                    cancellation = CancellationHandle { true },
                )

            assertEquals(Outcome.Failure(AppError.Cancelled), outcome)
            assertEquals(0, transport.calls)
        }

    @Test
    fun cancellationDuringTheRequestDiscardsTheBody() =
        runTest {
            var cancelled = false
            val transport =
                PhotonTransport {
                    cancelled = true
                    SUCCESS
                }
            val provider = provider(transport = transport)

            val outcome =
                provider.search(
                    query = "lviv",
                    context = SearchContext(),
                    limit = 5,
                    cancellation = CancellationHandle { cancelled },
                )

            assertEquals(Outcome.Failure(AppError.Cancelled), outcome)
        }

    private fun provider(
        gate: NetworkGate = AllowGate(),
        transport: PhotonTransport,
        minIntervalMillis: Long = 0,
        pause: suspend (Long) -> Unit = {},
    ): PhotonPlaceProvider =
        PhotonPlaceProvider(
            gate = gate,
            transport = transport,
            minIntervalMillis = minIntervalMillis,
            clock = { 0L },
            pause = pause,
        )

    private fun queryValue(
        url: String,
        name: String,
    ): String? =
        url
            .substringAfter('?', "")
            .split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "")
                val value = part.substringAfter('=', "")
                if (key == name) URLDecoder.decode(value, StandardCharsets.UTF_8) else null
            }.firstOrNull()

    private class AllowGate : NetworkGate {
        override suspend fun <T> request(
            switch: nl.r1a.grassmap.plugin.api.contract.SwitchId,
            block: suspend () -> Outcome<T>,
        ): Outcome<T> = block()
    }

    private class RefusingGate : NetworkGate {
        override suspend fun <T> request(
            switch: nl.r1a.grassmap.plugin.api.contract.SwitchId,
            block: suspend () -> Outcome<T>,
        ): Outcome<T> = Outcome.Failure(AppError.SwitchOff(switch))
    }

    private class RecordingTransport(
        vararg responses: Outcome<String>,
    ) : PhotonTransport {
        private val remaining = ArrayDeque(responses.toList())
        val urls = mutableListOf<String>()
        val calls: Int
            get() = urls.size

        override suspend fun get(url: String): Outcome<String> {
            urls += url
            return remaining.removeFirstOrNull() ?: Outcome.Failure(AppError.Network)
        }
    }

    private companion object {
        val SUCCESS: Outcome<String> = Outcome.Success("""{"type":"FeatureCollection","features":[]}""")

        val TWO_FEATURES: Outcome<String> =
            Outcome.Success(
                """
                {"type":"FeatureCollection","features":[
                  {"geometry":{"coordinates":[24.035,49.845]},"properties":{"name":"Inside"}},
                  {"geometry":{"coordinates":[24.10,49.90]},"properties":{"name":"Outside"}}
                ]}
                """.trimIndent(),
            )
    }
}
