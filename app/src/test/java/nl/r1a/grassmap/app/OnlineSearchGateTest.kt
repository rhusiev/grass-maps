package nl.r1a.grassmap.app

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.core.services.PrivacyNetworkGate
import nl.r1a.grassmap.core.services.PrivacySwitchSettings
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import nl.r1a.grassmap.plugin.api.service.NetworkSwitches
import nl.r1a.grassmap.plugins.online.search.PhotonPlaceProvider
import nl.r1a.grassmap.plugins.online.search.PhotonTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real privacy gate in front of the real provider: with the switch off the
 * transport records zero calls; with it on the same call reaches the transport
 * and is counted once (T-0016 reused, T-0212).
 */
class OnlineSearchGateTest {
    private val switches = PrivacySwitchSettings()

    @Test
    fun theOffSwitchRefusesBeforeTheTransportIsTouched() =
        runTest {
            val transport = RecordingTransport()
            val provider = provider(transport)

            val outcome =
                provider.search(
                    query = "Львів",
                    context = SearchContext(),
                    limit = 5,
                    cancellation = CancellationHandle.None,
                )

            assertEquals(Outcome.Failure(AppError.SwitchOff(NetworkSwitches.onlineSearch)), outcome)
            assertEquals(0, transport.calls)
            assertEquals(0L, switches.state(NetworkSwitches.onlineSearch).requests)
        }

    @Test
    fun theOnSwitchLetsTheSameCallThroughAndCountsIt() =
        runTest {
            val transport = RecordingTransport()
            val provider = provider(transport)
            switches.setEnabled(NetworkSwitches.onlineSearch, true)

            val outcome =
                provider.search(
                    query = "Львів",
                    context = SearchContext(),
                    limit = 5,
                    cancellation = CancellationHandle.None,
                )

            assertTrue((outcome as Outcome.Success).value.isEmpty())
            assertEquals(1, transport.calls)
            assertEquals(1L, switches.state(NetworkSwitches.onlineSearch).requests)
        }

    private fun provider(transport: PhotonTransport): PhotonPlaceProvider =
        PhotonPlaceProvider(
            gate = PrivacyNetworkGate(switches),
            transport = transport,
            minIntervalMillis = 0,
        )

    private class RecordingTransport : PhotonTransport {
        var calls: Int = 0
            private set

        override suspend fun get(url: String): Outcome<String> {
            calls += 1
            return Outcome.Success("""{"type":"FeatureCollection","features":[]}""")
        }
    }
}
