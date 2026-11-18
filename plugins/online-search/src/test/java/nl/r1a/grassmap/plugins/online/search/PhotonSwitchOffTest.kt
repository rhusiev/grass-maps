package nl.r1a.grassmap.plugins.online.search

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.core.services.PrivacyNetworkGate
import nl.r1a.grassmap.core.services.PrivacySwitchSettings
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import nl.r1a.grassmap.plugin.api.service.NetworkSwitches
import org.junit.Assert.assertEquals
import org.junit.Test

/** The online-search switch turned off during a search request (T-0616). */
class PhotonSwitchOffTest {
    private val switches = PrivacySwitchSettings()
    private var chunksRead = 0
    private val provider =
        PhotonPlaceProvider(
            gate = PrivacyNetworkGate(switches),
            transport = ChunkedTransport(),
            minIntervalMillis = 0,
        )

    @Test
    fun switchingOffMidSearchCancelsAtTheNextChunkAndCountsOnlyThePermittedContact() =
        runTest {
            switches.setEnabled(NetworkSwitches.onlineSearch, true)

            val outcome = search()

            assertEquals(Outcome.Failure(AppError.SwitchOff(NetworkSwitches.onlineSearch)), outcome)
            assertEquals(1, chunksRead)
            assertEquals(1L, switches.state(NetworkSwitches.onlineSearch).requests)
        }

    @Test
    fun aSearchWithTheSwitchOffNeverReadsAChunk() =
        runTest {
            val outcome = search()

            assertEquals(Outcome.Failure(AppError.SwitchOff(NetworkSwitches.onlineSearch)), outcome)
            assertEquals(0, chunksRead)
            assertEquals(0L, switches.state(NetworkSwitches.onlineSearch).requests)
        }

    private suspend fun search() = provider.search("Львів", SearchContext(), limit = 5, cancellation = CancellationHandle.None)

    /** Reads chunks, turns the switch off after the first, and checks for cancellation before each read. */
    private inner class ChunkedTransport : PhotonTransport {
        override suspend fun get(url: String): Outcome<String> {
            repeat(CHUNKS) {
                currentCoroutineContext().ensureActive()
                chunksRead++
                switches.setEnabled(NetworkSwitches.onlineSearch, false)
            }
            return Outcome.Success("{}")
        }
    }

    private companion object {
        const val CHUNKS = 3
    }
}
