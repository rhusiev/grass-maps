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
    fun aPresetThatForbidsOfflineIsRefusedBeforeAnyRequest() =
        runTest {
            TileServer().use { server ->
                val osm = RasterPresets.openStreetMap.copy(tileUrlTemplate = "${server.baseUrl}/{z}/{x}/{y}.png")
                val plan = RasterAreaPlan.of(osm, LVIV, minZoom = 6, maxZoom = 7)

                val outcome = downloader(OpenGate).download(plan, AREA_ID, VERSION, "Lviv", staging)

                assertEquals(Outcome.Failure(AppError.Unsupported), outcome)
                assertTrue(server.paths().isEmpty())
                val eox = RasterPresets.eox2018.copy(tileUrlTemplate = "${server.baseUrl}/{z}/{y}/{x}.jpg")
                val eoxPlan = RasterAreaPlan.of(eox, LVIV, minZoom = 6, maxZoom = 7)

                assertEquals(
                    Outcome.Failure(AppError.Unsupported),
                    downloader(OpenGate).download(eoxPlan, AREA_ID, VERSION, "Lviv", staging),
                )
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

    @Test
    fun tilesLargerThanTheEstimateStopTheDownloadAtTheCap() =
        runTest {
            ScriptedTileServer { _, _ -> Reply(body = ByteArray(BIG_TILE_BYTES)) }.use { server ->
                val plan = RasterAreaPlan.of(localTo(server.baseUrl), LVIV, minZoom = 6, maxZoom = 8)
                val capped = RasterAreaDownloader(RasterTileClient(gate = OpenGate), spacingMillis = 0, maxBytes = CAP_BYTES) { CREATED_AT }

                val outcome = capped.download(plan, AREA_ID, VERSION, "Lviv", staging)

                assertEquals(Outcome.Failure(AppError.Internal(RasterAreaDownloader.TOO_LARGE_ID)), outcome)
                assertTrue(server.paths().size < plan.tileCount)
                assertFalse(File(staging, "raster.pmtiles").exists())
            }
        }

    @Test
    fun freeSpaceThatShrinksBelowWhatAssemblyNeedsStopsTheDownload() =
        runTest {
            ScriptedTileServer { _, _ -> Reply(body = ByteArray(BIG_TILE_BYTES)) }.use { server ->
                val plan = RasterAreaPlan.of(localTo(server.baseUrl), LVIV, minZoom = 6, maxZoom = 8)
                val tight =
                    RasterAreaDownloader(
                        RasterTileClient(gate = OpenGate),
                        spacingMillis = 0,
                        freeBytes = { BIG_TILE_BYTES.toLong() },
                    ) { CREATED_AT }

                val outcome = tight.download(plan, AREA_ID, VERSION, "Lviv", staging)

                assertEquals(Outcome.Failure(AppError.Internal(RasterAreaDownloader.NO_SPACE_ID)), outcome)
                assertEquals(1, server.paths().size)
            }
        }

    @Test
    fun aMissingTileCountsAsProgressAndWaitsTheSpacing() =
        runTest {
            ScriptedTileServer { index, _ -> if (index == 0) Reply(status = NOT_FOUND) else Reply() }.use { server ->
                val plan = RasterAreaPlan.of(localTo(server.baseUrl), LVIV, minZoom = 6, maxZoom = 7)
                val progress = mutableListOf<RasterAreaProgress>()
                val spaced = RasterAreaDownloader(RasterTileClient(gate = OpenGate), spacingMillis = SPACING_MS) { CREATED_AT }

                val outcome = spaced.download(plan, AREA_ID, VERSION, "Lviv", staging, onProgress = progress::add)

                assertTrue(outcome is Outcome.Success)
                assertEquals(RasterAreaProgress(1, plan.tileCount), progress.first())
                assertEquals(plan.tileCount * SPACING_MS, testScheduler.currentTime)
            }
        }

    @Test
    fun aFailedTileWaitsBeforeItsRetry() =
        runTest {
            ScriptedTileServer { index, _ -> if (index == 0) Reply(status = SERVER_ERROR) else Reply() }.use { server ->
                val plan = RasterAreaPlan.of(localTo(server.baseUrl), LVIV, minZoom = 6, maxZoom = 7)
                val waiting =
                    RasterAreaDownloader(RasterTileClient(gate = OpenGate), spacingMillis = 0, retryDelayMillis = RETRY_MS) { CREATED_AT }

                val outcome = waiting.download(plan, AREA_ID, VERSION, "Lviv", staging)

                assertTrue(outcome is Outcome.Success)
                assertEquals(plan.tileCount.toInt() + 1, server.paths().size)
                assertEquals(RETRY_MS, testScheduler.currentTime)
            }
        }

    @Test
    fun aRateLimitedTileWaitsForRetryAfterAndTheDownloadGoesOn() =
        runTest {
            ScriptedTileServer { index, _ ->
                if (index == 0) Reply(status = TOO_MANY, headers = mapOf("Retry-After" to "$RETRY_AFTER_S")) else Reply()
            }.use { server ->
                val plan = RasterAreaPlan.of(localTo(server.baseUrl), LVIV, minZoom = 6, maxZoom = 7)

                val outcome = downloader(OpenGate).download(plan, AREA_ID, VERSION, "Lviv", staging)

                assertTrue(outcome is Outcome.Success)
                assertEquals(plan.tileCount.toInt() + 1, server.paths().size)
                assertEquals(RETRY_AFTER_S * MILLIS_PER_S, testScheduler.currentTime)
            }
        }

    private fun localTo(baseUrl: String): RasterSource = RasterPresets.eox2016.copy(tileUrlTemplate = "$baseUrl/{z}/{y}/{x}.jpg")

    private fun local(server: TileServer): RasterSource = RasterPresets.eox2016.copy(tileUrlTemplate = "${server.baseUrl}/{z}/{y}/{x}.jpg")

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
        const val AREA_ID = "raster-eox-s2-2016-test"
        const val VERSION = "1"
        const val CREATED_AT = "2026-11-17T00:00:00Z"
        const val SERVER_ERROR = 500
        const val NOT_FOUND = 404
        const val TOO_MANY = 429
        const val BIG_TILE_BYTES = 40_000
        const val CAP_BYTES = 100_000L
        const val SPACING_MS = 100L
        const val RETRY_MS = 500L
        const val RETRY_AFTER_S = 7L
        const val MILLIS_PER_S = 1_000L
        val LVIV = BoundingBox(GeoPoint(49.83, 24.00), GeoPoint(49.85, 24.05))
    }
}
