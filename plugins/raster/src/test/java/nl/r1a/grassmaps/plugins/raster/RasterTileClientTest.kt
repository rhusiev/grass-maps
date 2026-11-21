package nl.r1a.grassmaps.plugins.raster

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmaps.plugin.api.contract.SwitchId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.service.NetworkGate
import nl.r1a.grassmaps.plugin.api.service.NetworkSwitches
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tile client: the switch, the URL it builds, and the local server path. */
class RasterTileClientTest {
    @Test
    fun aRefusingGateNeverReachesTheTransport() =
        runTest {
            val transport = RecordingTransport()
            val client = RasterTileClient(gate = RefusingGate(), transport = transport)

            val outcome = client.fetch(RasterPresets.openStreetMap, z = 5, x = 10, y = 20)

            assertEquals(
                Outcome.Failure(AppError.SwitchOff(NetworkSwitches.onlineTiles)),
                outcome,
            )
            assertEquals(0, transport.calls)
        }

    @Test
    fun anAllowedRequestCarriesTheTileUrlAndTheUserAgent() =
        runTest {
            val transport = RecordingTransport()
            val client = RasterTileClient(gate = AllowingGate(), transport = transport)

            val outcome = client.fetch(RasterPresets.openStreetMap, z = 5, x = 10, y = 20)

            assertTrue(outcome is Outcome.Success)
            assertEquals(1, transport.calls)
            assertEquals(
                "https://tile.openstreetmap.org/5/10/20.png",
                transport.urls.single(),
            )
            assertTrue(transport.userAgents.single().contains("GrassMaps"))
            assertTrue(transport.userAgents.single().contains("tile"))
        }

    @Test
    fun aTileOutsideTheSourceIsRefusedBeforeTheGate() =
        runTest {
            val gate = CountingGate()
            val client = RasterTileClient(gate = gate, transport = RecordingTransport())

            val outcome = client.fetch(RasterPresets.openStreetMap, z = 20, x = 0, y = 0)

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
            assertEquals(0, gate.calls)
        }

    @Test
    fun theCustomTemplateFetchesOneTileFromTheLocalServer() =
        runTest {
            TileServer().use { server ->
                val source =
                    custom(
                        server.baseUrl + "/{z}/{x}/{y}.png",
                    )
                val client = RasterTileClient(gate = AllowingGate(), transport = HttpRasterTileTransport())

                val outcome = client.fetch(source, z = 3, x = 4, y = 5)

                val image = outcome as? Outcome.Success<TileImage> ?: throw AssertionError("no tile: $outcome")
                assertArrayEquals(TileServer.TILE_BYTES, image.value.bytes)
                assertEquals("/3/4/5.png", server.paths().single())
            }
        }

    @Test
    fun aServerErrorIsTypedAndDoesNotBecomeATile() =
        runTest {
            TileServer(status = 404).use { server ->
                val source = custom(server.baseUrl + "/{z}/{x}/{y}.png")
                val client = RasterTileClient(gate = AllowingGate(), transport = HttpRasterTileTransport())

                val outcome = client.fetch(source, z = 0, x = 0, y = 0)

                assertEquals(Outcome.Failure(AppError.Server(status = 404)), outcome)
            }
        }

    @Test
    fun aBodyThatIsNotAnImageIsCorruptData() =
        runTest {
            TileServer(contentType = "text/html").use { server ->
                val source = custom(server.baseUrl + "/{z}/{x}/{y}.png")
                val client = RasterTileClient(gate = AllowingGate(), transport = HttpRasterTileTransport())

                val outcome = client.fetch(source, z = 0, x = 0, y = 0)

                assertEquals(Outcome.Failure(AppError.CorruptData), outcome)
            }
        }

    private fun custom(template: String): RasterSource =
        when (val outcome = RasterSources.custom(template)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("template refused: ${outcome.error}")
        }

    private class AllowingGate : NetworkGate {
        override suspend fun <T> request(
            switch: SwitchId,
            block: suspend () -> Outcome<T>,
        ): Outcome<T> = block()
    }

    private class RefusingGate : NetworkGate {
        override suspend fun <T> request(
            switch: SwitchId,
            block: suspend () -> Outcome<T>,
        ): Outcome<T> = Outcome.Failure(AppError.SwitchOff(switch))
    }

    private class CountingGate : NetworkGate {
        var calls: Int = 0
            private set

        override suspend fun <T> request(
            switch: SwitchId,
            block: suspend () -> Outcome<T>,
        ): Outcome<T> {
            calls += 1
            return block()
        }
    }

    private class RecordingTransport : RasterTileTransport {
        val urls = mutableListOf<String>()
        val userAgents = mutableListOf<String>()

        val calls: Int
            get() = urls.size

        override suspend fun get(
            url: String,
            userAgent: String,
        ): Outcome<TileImage> {
            urls += url
            userAgents += userAgent
            return Outcome.Success(TileImage(bytes = TileServer.TILE_BYTES, contentType = TileServer.PNG))
        }
    }
}
