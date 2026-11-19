package nl.r1a.grassmap.plugins.downloads.ui

import nl.r1a.grassmap.plugin.api.model.CatalogPartKind
import nl.r1a.grassmap.plugin.api.model.DownloadProblem
import nl.r1a.grassmap.plugin.api.model.DownloadProblemKind
import nl.r1a.grassmap.plugin.api.model.DownloadRowStatus
import nl.r1a.grassmap.plugin.api.model.DownloadTransfer
import nl.r1a.grassmap.plugin.api.model.DownloadTransferState
import nl.r1a.grassmap.plugin.api.model.DownloadsData
import nl.r1a.grassmap.plugin.api.model.DownloadsDefaults
import nl.r1a.grassmap.plugin.api.model.FirstRunOfferKind
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.InstalledPack
import nl.r1a.grassmap.plugin.api.model.RegionProblem
import nl.r1a.grassmap.plugin.api.testing.fixture.CatalogFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The downloads screen as a derivation of the store's data (T-0504).
 *
 * The catalogue is the real T-0501 output; the world entry and the 30 m add-on
 * come from the documented-shape variants, because neither is built yet.
 */
class DownloadsScreenTest {
    @Test
    fun theRealCatalogueDrawsOneAvailableRowPerOblastWithItsDataDate() {
        val state = DownloadsScreen.state(DownloadsData(catalog = CatalogTestData.real(), serverUrl = SERVER))

        assertEquals(CatalogFixture.REGION_COUNT, state.availableRows.size)
        assertTrue(state.installedRows.isEmpty())
        assertTrue(state.catalogLoaded)
        assertNull(state.catalogProblem)

        val lviv = state.availableRows.single { it.region == CatalogFixture.LVIV }
        assertEquals("Lviv Oblast", lviv.nameEn)
        assertEquals("Львівська область", lviv.nameUk)
        assertEquals("2026-11-03", lviv.dataDate)
        assertEquals(210_943_205L, lviv.bytes)
        assertEquals(DownloadRowStatus.AVAILABLE, lviv.status)
        assertEquals(
            listOf(
                CatalogPartKind.CONTOURS,
                CatalogPartKind.ELEVATION,
                CatalogPartKind.MAP,
                CatalogPartKind.ROUTING,
                CatalogPartKind.SEARCH,
            ).sorted(),
            lviv.parts.map { it.kind }.sorted(),
        )
        assertEquals(65_699_471L, lviv.parts.single { it.kind == CatalogPartKind.MAP }.bytes)
        assertTrue(lviv.parts.none { it.installed })
    }

    @Test
    fun firstRunOffersTheWorldThenTheDetectedOblastWithItsPartsAndSizes() {
        val catalog = CatalogTestData.withWorldAndAddon()
        val state =
            DownloadsScreen.state(
                data = DownloadsData(catalog = catalog, serverUrl = SERVER),
                detectedRegion = CatalogFixture.LVIV,
            )

        assertEquals(
            listOf(
                FirstRunOfferKind.WORLD,
                FirstRunOfferKind.REGION,
                FirstRunOfferKind.ROUTING,
            ),
            state.firstRun.map { it.kind },
        )

        val world = state.firstRun.first()
        assertEquals(CatalogFixture.WORLD_ID, world.region)
        assertEquals(74_000_000L, world.bytes)
        assertEquals(listOf(CatalogPartKind.MAP), world.parts.map { it.kind })

        val region = state.firstRun.single { it.kind == FirstRunOfferKind.REGION }
        assertEquals(CatalogFixture.LVIV, region.region)
        assertEquals(
            listOf(
                CatalogPartKind.MAP,
                CatalogPartKind.SEARCH,
                CatalogPartKind.ELEVATION,
                CatalogPartKind.CONTOURS,
            ).sorted(),
            region.parts.map { it.kind }.sorted(),
        )
        assertEquals(region.parts.sumOf { it.bytes }, region.bytes)
        assertTrue(region.parts.all { it.bytes > 0 })

        val routing = state.firstRun.single { it.kind == FirstRunOfferKind.ROUTING }
        assertEquals(97_198_080L, routing.bytes)
        assertEquals(listOf(CatalogPartKind.ROUTING), routing.parts.map { it.kind })
    }

    @Test
    fun theAddonIsOfferedOnlyOnceItsRegionIsInstalled() {
        val catalog = CatalogTestData.withWorldAndAddon()
        val installed = InstalledPack(CatalogFixture.LVIV, "old", parts = listOf(CatalogPartKind.MAP))
        val state =
            DownloadsScreen.state(
                data = DownloadsData(catalog = catalog, installed = listOf(installed), serverUrl = SERVER),
                detectedRegion = CatalogFixture.LVIV,
            )

        val addon = state.firstRun.single { it.kind == FirstRunOfferKind.ELEVATION_30M }
        assertEquals(13_000_000L, addon.bytes)
        assertEquals(CatalogPartKind.ELEVATION_30M, addon.parts.single().kind)
        assertEquals("${CatalogFixture.LVIV}-elevation-30m", addon.downloadId)
        assertFalse(addon.installed)
    }

    @Test
    fun theAddonIsInstalledWhenTheRegionPackReportsIt() {
        val catalog = CatalogTestData.withWorldAndAddon()
        val installed = InstalledPack(CatalogFixture.LVIV, "old", parts = listOf(CatalogPartKind.ELEVATION_30M))
        val state =
            DownloadsScreen.state(
                data = DownloadsData(catalog = catalog, installed = listOf(installed), serverUrl = SERVER),
                detectedRegion = CatalogFixture.LVIV,
            )

        val addon = state.firstRun.single { it.kind == FirstRunOfferKind.ELEVATION_30M }
        assertTrue(addon.installed)
        assertTrue(addon.parts.single().installed)
    }

    @Test
    fun theDetectedOblastComesFromTheFixAndNeverFromTheWorldEntry() {
        val catalog = CatalogTestData.withWorld()

        assertEquals(
            CatalogFixture.LVIV,
            DownloadsScreen.detectRegion(catalog, GeoPoint(49.84, 24.03)),
        )
        assertNull(DownloadsScreen.detectRegion(catalog, GeoPoint(0.0, 0.0)))
        assertNull(DownloadsScreen.detectRegion(catalog, fix = null))
        assertNull(DownloadsScreen.detectRegion(catalog = null, fix = GeoPoint(49.84, 24.03)))
    }

    @Test
    fun withoutADetectedRegionOnlyTheWorldIsOffered() {
        val state =
            DownloadsScreen.state(
                data = DownloadsData(catalog = CatalogTestData.withWorld(), serverUrl = SERVER),
                detectedRegion = null,
            )

        assertEquals(listOf(FirstRunOfferKind.WORLD), state.firstRun.map { it.kind })
        // The world offer already names the world row, so the list does not repeat it.
        assertEquals(CatalogFixture.REGION_COUNT, state.availableRows.size)
    }

    @Test
    fun anInstalledRegionShowsItsDateItsPartsAndTheSheet() {
        val installed =
            InstalledPack(
                region = CatalogFixture.LVIV,
                version = CatalogFixture.VERSION,
                bytes = 210_943_205L,
                parts = listOf(CatalogPartKind.MAP, CatalogPartKind.CONTOURS, CatalogPartKind.SEARCH, CatalogPartKind.ELEVATION),
                dataDate = "2026-11-03",
            )
        val state =
            DownloadsScreen.state(
                data = DownloadsData(catalog = CatalogTestData.real(), installed = listOf(installed), serverUrl = SERVER),
                selectedRegion = CatalogFixture.LVIV,
            )

        val row = state.installedRows.single()
        assertEquals(CatalogFixture.LVIV, row.region)
        assertEquals(DownloadRowStatus.INSTALLED, row.status)
        assertEquals("2026-11-03", row.dataDate)
        assertEquals(CatalogFixture.VERSION, row.installedVersion)
        assertEquals(210_943_205L, row.installedBytes)
        assertTrue(row.parts.filter { it.kind != CatalogPartKind.ROUTING }.all { it.installed })
        assertFalse(row.parts.single { it.kind == CatalogPartKind.ROUTING }.installed)

        assertEquals(CatalogFixture.LVIV, state.selectedRegion)
        assertEquals(CatalogFixture.LVIV, state.selected?.region)
        assertEquals(CatalogFixture.REGION_COUNT - 1, state.availableRows.size)
    }

    @Test
    fun theNextStartHintShowsOnlyWhenAnInstalledPackIsNewerThanTheMountedOne() {
        val mounted =
            listOf(
                InstalledPack(
                    CatalogFixture.LVIV,
                    "2026-10-01-0",
                    parts = listOf(CatalogPartKind.MAP, CatalogPartKind.ELEVATION),
                ),
            )
        val updated =
            listOf(
                InstalledPack(
                    CatalogFixture.LVIV,
                    CatalogFixture.VERSION,
                    parts = listOf(CatalogPartKind.MAP, CatalogPartKind.ELEVATION),
                ),
            )

        val unchanged = DownloadsData(installed = mounted, mountedPacks = mounted)
        val newer = DownloadsData(installed = updated, mountedPacks = mounted)

        assertFalse(DownloadsScreen.state(unchanged).appliesAtNextStart)
        assertFalse(DownloadsScreen.state(DownloadsData(installed = updated)).appliesAtNextStart)
        assertTrue(DownloadsScreen.state(newer).appliesAtNextStart)
    }

    @Test
    fun anOlderInstalledVersionStaysInThePhoneGroupAndShowsItsOwnDate() {
        val installed =
            InstalledPack(
                region = CatalogFixture.LVIV,
                version = "2026-10-01",
                bytes = 210_943_205L,
                parts = listOf(CatalogPartKind.MAP),
                dataDate = "2026-10-01",
            )
        val state =
            DownloadsScreen.state(DownloadsData(catalog = CatalogTestData.real(), installed = listOf(installed), serverUrl = SERVER))

        val row = state.installedRows.single()
        assertEquals(DownloadRowStatus.UPDATE_AVAILABLE, row.status)
        assertEquals("2026-10-01", row.installedVersion)
        // The row dates the data that is on the phone, not the catalogue's newer one.
        assertEquals("2026-10-01", row.dataDate)
        assertEquals("2026-11-03", row.version)
    }

    @Test
    fun aTransferMakesTheRowDownloadingAndCarriesItsProgress() {
        val transfer =
            DownloadTransfer(
                region = CatalogFixture.LVIV,
                state = DownloadTransferState.DOWNLOADING,
                bytesDone = 5_000_000L,
                bytesTotal = 10_000_000L,
            )
        val state =
            DownloadsScreen.state(
                DownloadsData(catalog = CatalogTestData.real(), transfers = listOf(transfer), serverUrl = SERVER),
            )

        val row = state.availableRows.single { it.region == CatalogFixture.LVIV }
        assertEquals(DownloadRowStatus.DOWNLOADING, row.status)
        assertEquals(transfer, row.transfer)
        assertEquals(50, DownloadsFormat.progressPercent(row.transfer!!.bytesDone, row.transfer!!.bytesTotal))
    }

    @Test
    fun aTokenProblemOnACatalogueReachesTheStateAsATypedProblem() {
        val problem = DownloadProblem(DownloadProblemKind.TOKEN_REQUIRED, status = 401)
        val state =
            DownloadsScreen.state(
                DownloadsData(serverUrl = SERVER, catalogProblem = problem),
            )

        assertEquals(problem, state.catalogProblem)
        assertFalse(state.catalogLoaded)
        assertTrue(state.availableRows.isEmpty())
    }

    @Test
    fun aFailedDownloadMarksTheRowAndKeepsTheProblem() {
        val problem = DownloadProblem(DownloadProblemKind.CORRUPT_DATA)
        val state =
            DownloadsScreen.state(
                DownloadsData(
                    catalog = CatalogTestData.real(),
                    problems = listOf(RegionProblem(CatalogFixture.LVIV, problem)),
                    serverUrl = SERVER,
                ),
            )

        val row = state.availableRows.single { it.region == CatalogFixture.LVIV }
        assertEquals(DownloadRowStatus.FAILED, row.status)
        assertEquals(problem, row.problem)
    }

    @Test
    fun aStaleSelectionOrDetectionDrawsNothing() {
        val state =
            DownloadsScreen.state(
                data = DownloadsData(catalog = CatalogTestData.real(), serverUrl = SERVER),
                selectedRegion = "ua-99",
                detectedRegion = "ua-99",
            )

        assertNull(state.selectedRegion)
        assertNull(state.selected)
        assertNull(state.detectedRegion)
        assertTrue(state.firstRun.isEmpty())
    }

    @Test
    fun anEmptyServerUrlFallsBackToTheShipsWithServer() {
        val state = DownloadsScreen.state(DownloadsData())

        assertEquals(DownloadsDefaults.SERVER_URL, state.serverUrl)
        assertFalse(state.tokenPresent)
    }

    @Test
    fun installedRowsKeepCatalogueOrder() {
        val installed =
            listOf("ua-46", "ua-05").map { id ->
                InstalledPack(region = id, version = CatalogFixture.VERSION, parts = listOf(CatalogPartKind.MAP))
            }
        val state =
            DownloadsScreen.state(DownloadsData(catalog = CatalogTestData.real(), installed = installed, serverUrl = SERVER))

        assertEquals(listOf("ua-05", "ua-46"), state.installedRows.map { it.region })
    }

    private companion object {
        const val SERVER = "https://maps.test"
    }
}
