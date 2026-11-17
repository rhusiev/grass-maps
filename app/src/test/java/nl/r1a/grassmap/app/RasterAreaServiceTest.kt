package nl.r1a.grassmap.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.service.NetworkGate
import nl.r1a.grassmap.plugin.api.service.NetworkSwitches
import nl.r1a.grassmap.plugins.raster.RasterAreaDownloadState
import nl.r1a.grassmap.plugins.raster.RasterAreaFailure
import nl.r1a.grassmap.plugins.raster.RasterAreaPlan
import nl.r1a.grassmap.plugins.raster.RasterPresets
import nl.r1a.grassmap.plugins.raster.RasterSource
import nl.r1a.grassmap.plugins.raster.RasterTileClient
import nl.r1a.grassmap.plugins.raster.RasterTileRange
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * An area from a local EOX mock becomes an installed `raster.pmtiles` that the
 * layer reads with the network gate off (T-0513).
 */
class RasterAreaServiceTest {
    private lateinit var root: File
    private lateinit var mock: EoxMockServer

    @Before
    fun setUp() {
        root = Files.createTempDirectory("raster-areas").toFile()
        mock = EoxMockServer()
    }

    @After
    fun tearDown() {
        mock.close()
        root.deleteRecursively()
    }

    @Test
    fun aDownloadedAreaInstallsAsAValidPmtilesPack() =
        runTest {
            val service = service(OpenGate)
            val plan = RasterAreaPlan.of(mockSource(), LVIV, minZoom = 6, maxZoom = 8)

            val state = service.download(plan, "Lviv")

            assertEquals(RasterAreaDownloadState.Done, state)
            val area = service.areas.value.single()
            assertEquals(plan.tileCount.toInt(), mock.requestCount)
            assertEquals(plan.tileCount, area.tileCount)
            assertEquals(6, area.minZoom)
            assertEquals(8, area.maxZoom)
            assertEquals(LVIV.southWest.latitude, area.bounds.southWest.latitude, BOUNDS_TOLERANCE)
            assertEquals(LVIV.northEast.longitude, area.bounds.northEast.longitude, BOUNDS_TOLERANCE)
            assertEquals("raster.pmtiles", area.file.name)
            assertEquals("PMTiles", area.file.inputStream().use { String(it.readNBytes(PMTILES_MAGIC)) })
            val range = RasterTileRange.of(LVIV, 8)
            assertArrayEquals(
                EoxMockServer.tileBody(8, range.minX.toInt(), range.minY.toInt()),
                area.tile(8, range.minX, range.minY),
            )
        }

    @Test
    fun theInstalledAreaReadsWithTheGateOffAndShowsItsLicence() =
        runTest {
            val online = service(OpenGate)
            online.download(RasterAreaPlan.of(mockSource(), LVIV, minZoom = 6, maxZoom = 7), "Lviv")
            val requestsBefore = mock.requestCount

            val offline = service(ClosedGate)
            offline.refresh()

            val area = offline.areas.value.single()
            val range = RasterTileRange.of(LVIV, 7)
            assertTrue(area.tile(7, range.minX, range.minY) != null)
            assertEquals(requestsBefore, mock.requestCount)
            val item = offline.items().single()
            assertFalse(item.licence.isNonCommercial)
            assertTrue(item.licence.name.contains("CC BY 4.0"))
            assertEquals(area.file.absolutePath, offline.layers().single().packFile)
        }

    @Test
    fun withTheGateOffNothingIsRequestedOrInstalled() =
        runTest {
            val service = service(ClosedGate)

            val state = service.download(RasterAreaPlan.of(mockSource(), LVIV, minZoom = 6, maxZoom = 7), "Lviv")

            assertEquals(RasterAreaDownloadState.Failed(RasterAreaFailure.SwitchOff), state)
            assertEquals(0, mock.requestCount)
            assertTrue(service.areas.value.isEmpty())
        }

    @Test
    fun presetsThatForbidOfflineAreRefusedWithoutARequest() =
        runTest {
            val service = service(OpenGate)
            listOf(RasterPresets.openStreetMap, RasterPresets.eox2018).forEach { preset ->
                val plan = RasterAreaPlan.of(preset.copy(tileUrlTemplate = mock.template), LVIV, minZoom = 6, maxZoom = 7)

                val state = service.download(plan, "Lviv")

                assertEquals(RasterAreaPlan.Refusal.SourceForbidsOffline, service.refusalOf(plan))
                assertEquals(RasterAreaDownloadState.Failed(RasterAreaFailure.Refused), state)
            }
            assertEquals(0, mock.requestCount)
        }

    @Test
    fun aSourceWithoutALicenceIsRefusedWithoutARequest() =
        runTest {
            val service = service(OpenGate)
            val plan = RasterAreaPlan.of(mockSource().copy(licence = null), LVIV, minZoom = 6, maxZoom = 7)

            assertEquals(RasterAreaPlan.Refusal.NoLicence, service.refusalOf(plan))
            assertEquals(RasterAreaDownloadState.Failed(RasterAreaFailure.Refused), service.download(plan, "Lviv"))
            assertEquals(0, mock.requestCount)
        }

    @Test
    fun theAreaIdIsSixteenHexDigitsOfAStableHash() =
        runTest {
            val service = service(OpenGate)
            val plan = RasterAreaPlan.of(mockSource(), LVIV, minZoom = 6, maxZoom = 7)
            service.download(plan, "Lviv")
            val id =
                service.areas.value
                    .single()
                    .id
            service.remove(id)
            service.download(plan, "Lviv")

            assertTrue(Regex("raster-[a-z0-9-]+-[0-9a-f]{16}").matches(id))
            assertEquals(
                id,
                service.areas.value
                    .single()
                    .id,
            )
        }

    @Test
    fun prepareDeletesRasterLeftoversOnceAndThenReadsTheStore() =
        runTest {
            val staging = File(root, ".incoming/raster-old-1-abc").apply { mkdirs() }
            val orphan = File(root, "raster-lost-aa/1").apply { mkdirs() }

            service(OpenGate).prepare()

            assertFalse(staging.exists())
            assertFalse(orphan.exists())
        }

    @Test
    fun anAreaPastTheTileCapIsRefusedBeforeTheDownload() =
        runTest {
            val service = service(OpenGate)
            val plan = RasterAreaPlan.of(mockSource(), WORLD, minZoom = 6, maxZoom = 14)

            assertEquals(RasterAreaPlan.Refusal.TooManyTiles, service.refusalOf(plan))
            assertEquals(RasterAreaDownloadState.Failed(RasterAreaFailure.Refused), service.download(plan, "World"))
            assertEquals(0, mock.requestCount)
        }

    @Test
    fun anAreaThatDoesNotFitTheFreeSpaceIsRefused() =
        runTest {
            val service = service(OpenGate, freeBytes = 1L)
            val plan = RasterAreaPlan.of(mockSource(), LVIV, minZoom = 6, maxZoom = 7)

            assertEquals(RasterAreaPlan.Refusal.NotEnoughSpace, service.refusalOf(plan))
            assertEquals(RasterAreaDownloadState.Failed(RasterAreaFailure.Refused), service.download(plan, "Lviv"))
            assertEquals(0, mock.requestCount)
        }

    @Test
    fun removingAnAreaDeletesItsPack() =
        runTest {
            val service = service(OpenGate)
            service.download(RasterAreaPlan.of(mockSource(), LVIV, minZoom = 6, maxZoom = 7), "Lviv")
            val id =
                service.areas.value
                    .single()
                    .id

            assertEquals(Outcome.Success(Unit), service.remove(id))

            assertTrue(service.areas.value.isEmpty())
            assertTrue(!File(root, id).exists())
        }

    private fun mockSource(): RasterSource = RasterPresets.eox2016.copy(tileUrlTemplate = mock.template)

    private fun service(
        gate: NetworkGate,
        freeBytes: Long? = null,
    ): RasterAreaService =
        RasterAreaService(
            root = root,
            client = RasterTileClient(gate = gate),
            scope = CoroutineScope(Dispatchers.Unconfined),
            spacingMillis = 0,
            freeBytes = { freeBytes },
        )

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
        ): Outcome<T> {
            check(switch == NetworkSwitches.onlineTiles)
            return Outcome.Failure(AppError.SwitchOff(switch))
        }
    }

    private companion object {
        const val BOUNDS_TOLERANCE = 1e-6
        const val PMTILES_MAGIC = 7
        val LVIV = BoundingBox(GeoPoint(49.83, 24.00), GeoPoint(49.85, 24.05))
        val WORLD = BoundingBox(GeoPoint(-80.0, -179.0), GeoPoint(80.0, 179.0))
    }
}
