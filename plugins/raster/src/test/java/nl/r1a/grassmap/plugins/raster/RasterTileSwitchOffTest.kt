package nl.r1a.grassmap.plugins.raster

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.core.services.PrivacyNetworkGate
import nl.r1a.grassmap.core.services.PrivacySwitchSettings
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.NetworkSwitches
import org.junit.Assert.assertEquals
import org.junit.Test

/** The online-tiles switch turned off during a tile request (T-0616). */
class RasterTileSwitchOffTest {
    private val switches = PrivacySwitchSettings()
    private val client = RasterTileClient(gate = PrivacyNetworkGate(switches), transport = ChunkedTransport())

    @Test
    fun switchingOffMidTileCancelsAtTheNextChunkAndCountsOnlyThePermittedContact() =
        runTest {
            switches.setEnabled(NetworkSwitches.onlineTiles, true)

            val outcome = client.fetch(RasterPresets.openStreetMap, z = 5, x = 10, y = 20)

            assertEquals(Outcome.Failure(AppError.SwitchOff(NetworkSwitches.onlineTiles)), outcome)
            assertEquals(1, chunksRead)
            assertEquals(1L, switches.state(NetworkSwitches.onlineTiles).requests)
        }

    @Test
    fun aTileRequestWithTheSwitchOffNeverReadsAChunk() =
        runTest {
            val outcome = client.fetch(RasterPresets.openStreetMap, z = 5, x = 10, y = 20)

            assertEquals(Outcome.Failure(AppError.SwitchOff(NetworkSwitches.onlineTiles)), outcome)
            assertEquals(0, chunksRead)
            assertEquals(0L, switches.state(NetworkSwitches.onlineTiles).requests)
        }

    private var chunksRead = 0

    /** Reads chunks, turns the switch off after the first, and checks for cancellation before each read. */
    private inner class ChunkedTransport : RasterTileTransport {
        override suspend fun get(
            url: String,
            userAgent: String,
        ): Outcome<TileImage> {
            repeat(CHUNKS) {
                currentCoroutineContext().ensureActive()
                chunksRead++
                switches.setEnabled(NetworkSwitches.onlineTiles, false)
            }
            return Outcome.Success(TileImage(bytes = ByteArray(1), contentType = "image/png"))
        }
    }

    private companion object {
        const val CHUNKS = 3
    }
}
