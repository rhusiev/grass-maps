package nl.r1a.grassmap.plugins.raster

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.service.NetworkGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The downloader fetches through the gate and writes a staged package (T-0513). */
class RasterAreaDownloaderTest {
    private lateinit var staging: File

    @Before
    fun setUp() {
        staging = Files.createTempDirectory("raster-staging").toFile()
    }

    @After
    fun tearDown() {
        staging.deleteRecursively()
    }

    @Test
    fun aSmallAreaBecomesAStagedPackWithAManifestAndAPmtilesFile() =
        runTest {
            TileServer(contentType = "image/jpeg").use { server ->
                val source = local(server)
                val plan = RasterAreaPlan.of(source, LVIV, minZoom = 6, maxZoom = 7)
                val progress = mutableListOf<RasterAreaProgress>()

                val outcome = downloader(OpenGate).download(plan, AREA_ID, VERSION, "Lviv", staging, onProgress = progress::add)

                assertTrue(outcome is Outcome.Success)
                assertEquals(plan.tileCount.toInt(), server.paths().size)
                assertTrue(File(staging, "raster.pmtiles").isFile)
                assertTrue(File(staging, "manifest.json").isFile)
                assertFalse(File(staging, "tiles.work").exists())
                assertEquals(RasterAreaProgress(plan.tileCount, plan.tileCount), progress.last())
            }
        }

    @Test
    fun theOpenStreetMapPresetIsRefusedBeforeAnyRequest() =
        runTest {
            TileServer().use { server ->
                val plan = RasterAreaPlan.of(RasterPresets.openStreetMap, LVIV, minZoom = 6, maxZoom = 7)

                val outcome = downloader(OpenGate).download(plan, AREA_ID, VERSION, "Lviv", staging)

                assertEquals(Outcome.Failure(AppError.Unsupported), outcome)
                assertTrue(server.paths().isEmpty())
            }
        }

    @Test
    fun aClosedGateOpensNoSocket() =
        runTest {
            TileServer(contentType = "image/jpeg").use { server ->
                val plan = RasterAreaPlan.of(local(server), LVIV, minZoom = 6, maxZoom = 7)

                val outcome = downloader(ClosedGate).download(plan, AREA_ID, VERSION, "Lviv", staging)

                assertTrue((outcome as Outcome.Failure).error is AppError.SwitchOff)
                assertTrue(server.paths().isEmpty())
                assertFalse(File(staging, "raster.pmtiles").exists())
            }
        }

    @Test
    fun aCancelledDownloadStopsAndStagesNoPack() =
        runTest {
            TileServer(contentType = "image/jpeg").use { server ->
                val plan = RasterAreaPlan.of(local(server), LVIV, minZoom = 6, maxZoom = 8)

                val outcome =
                    downloader(OpenGate).download(
                        plan,
                        AREA_ID,
                        VERSION,
                        "Lviv",
                        staging,
                        cancellation = { server.paths().isNotEmpty() },
                    )

                assertEquals(Outcome.Failure(AppError.Cancelled), outcome)
                assertEquals(1, server.paths().size)
                assertFalse(File(staging, "raster.pmtiles").exists())
            }
        }

    @Test
    fun aServerThatReturnsAnErrorEndsTheDownload() =
        runTest {
            TileServer(status = SERVER_ERROR, contentType = "image/jpeg").use { server ->
                val plan = RasterAreaPlan.of(local(server), LVIV, minZoom = 6, maxZoom = 7)

                val outcome = downloader(OpenGate).download(plan, AREA_ID, VERSION, "Lviv", staging)

                assertTrue((outcome as Outcome.Failure).error is AppError.Server)
                assertFalse(File(staging, "raster.pmtiles").exists())
            }
        }

    private fun local(server: TileServer): RasterSource = RasterPresets.eox2018.copy(tileUrlTemplate = "${server.baseUrl}/{z}/{y}/{x}.jpg")

    private fun downloader(gate: NetworkGate): RasterAreaDownloader =
        RasterAreaDownloader(RasterTileClient(gate = gate), spacingMillis = 0) { CREATED_AT }

    private object OpenGate : NetworkGate {
        override suspend fun <T> request(
            switch: SwitchId,
            block: suspend () -> Outcome<T>,
        ): Outcome<T> = block()
    }

    private object ClosedGate : NetworkGate {
        override suspend fun <T> request(
            switch: SwitchId,
            block: suspend () -> Outcome<T>,
        ): Outcome<T> = Outcome.Failure(AppError.SwitchOff(switch))
    }

    private companion object {
        const val AREA_ID = "raster-eox-s2-2018-test"
        const val VERSION = "1"
        const val CREATED_AT = "2026-11-17T00:00:00Z"
        const val SERVER_ERROR = 500
        val LVIV = BoundingBox(GeoPoint(49.83, 24.00), GeoPoint(49.85, 24.05))
    }
}
