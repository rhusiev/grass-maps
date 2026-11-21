package nl.r1a.grassmaps.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmaps.core.services.PrivacyNetworkGate
import nl.r1a.grassmaps.core.services.PrivacySwitchSettings
import nl.r1a.grassmaps.places.MergedPlaceSearch
import nl.r1a.grassmaps.places.SearchScreenCommand
import nl.r1a.grassmaps.places.SearchScreenRequest
import nl.r1a.grassmaps.places.SearchScreenSession
import nl.r1a.grassmaps.places.SearchScreenStatus
import nl.r1a.grassmaps.plugin.api.contract.CancellationHandle
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.provider.PlaceHit
import nl.r1a.grassmaps.plugin.api.provider.PlaceSource
import nl.r1a.grassmaps.plugin.api.provider.SearchContext
import nl.r1a.grassmaps.plugin.api.service.NetworkSwitches
import nl.r1a.grassmaps.plugins.online.search.PhotonPlaceProvider
import nl.r1a.grassmaps.plugins.online.search.PhotonTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merged search swallows the online provider's typed refusal when an
 * offline index answers (T-0616). These proofs turn the privacy switch off
 * after the first body chunk of a real Photon request and follow the note to
 * the caller, the screen state and the control result.
 */
class MergedSearchSwitchOffTest {
    private val switches = PrivacySwitchSettings()
    private var chunksRead = 0
    private val provider =
        PhotonPlaceProvider(
            gate = PrivacyNetworkGate(switches),
            transport = ChunkedTransport(),
            minIntervalMillis = 0,
        )

    @Test
    fun aSwitchOffAfterTheFirstChunkReachesTheCallerWithOfflineHitsIntact() =
        runTest {
            switches.setEnabled(NetworkSwitches.onlineSearch, true)
            var noted: AppError.SwitchOff? = null

            val outcome =
                mergedSearch().search(
                    request(onOnlineStopped = { noted = it }),
                )

            assertEquals(1, chunksRead)
            assertEquals(AppError.SwitchOff(NetworkSwitches.onlineSearch), noted)
            val hits = (outcome as Outcome.Success).value
            assertEquals(listOf("offline"), hits.map { it.id })
            assertEquals(listOf(PlaceSource.OFFLINE), hits.map { it.source })
        }

    @Test
    fun theTypedNoteReachesTheScreenAndTheControlResult() =
        runTest {
            switches.setEnabled(NetworkSwitches.onlineSearch, true)
            val session = SearchScreenSession { request -> mergedSearch().search(request) }
            val logged = mutableListOf<String>()
            val command = SearchScreenCommand(session, log = { logged += it })

            val outcome =
                command.execute(
                    buildJsonObject {
                        put("query", "львів")
                        put("sequence", 1)
                    },
                )

            assertEquals(AppError.SwitchOff(NetworkSwitches.onlineSearch), session.state.value.onlineStopped)
            assertEquals(SearchScreenStatus.RESULTS, session.state.value.status)
            assertEquals(listOf("offline"), session.state.value.hits.map { it.id })
            val result = (outcome as Outcome.Success).value.value
            assertTrue("the control result must name the switch: $result", result.contains("switchOff=network.online_search"))
            assertTrue(
                "the log must name the typed refusal: $logged",
                logged.any { it.contains("SwitchOff(switch=network.online_search)") },
            )
        }

    private fun mergedSearch(): MergedPlaceSearch =
        MergedPlaceSearch(
            offline = { Outcome.Success(listOf(offlineHit())) },
            online = { request ->
                provider.search(request.query, request.context, request.limit, request.cancellation)
            },
            onlineEnabled = { true },
            positionAllowed = { false },
            debounceMillis = 0,
        )

    private fun request(
        onOnlineStopped: suspend (AppError.SwitchOff) -> Unit = {},
    ): SearchScreenRequest =
        SearchScreenRequest(
            query = "львів",
            index = "lviv",
            limit = 5,
            context = SearchContext(),
            cancellation = CancellationHandle.None,
            onOnlineStopped = onOnlineStopped,
        )

    private fun offlineHit(): PlaceHit =
        PlaceHit(
            id = "offline",
            name = "Площа Ринок",
            kind = "place=square",
            position = GeoPoint(49.8413, 24.0312),
            address = "Львів",
            regionBuild = "lviv",
            score = 1f,
            source = PlaceSource.OFFLINE,
        )

    /** Reads chunks, turns the switch off after the first, and checks before each read. */
    private inner class ChunkedTransport : PhotonTransport {
        override suspend fun get(url: String): Outcome<String> {
            repeat(CHUNKS) {
                currentCoroutineContext().ensureActive()
                chunksRead++
                if (chunksRead == 1) {
                    switches.setEnabled(NetworkSwitches.onlineSearch, false)
                }
            }
            return Outcome.Success("{}")
        }
    }

    private companion object {
        const val CHUNKS = 3
    }
}
