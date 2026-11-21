package nl.r1a.grassmaps.plugin.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The update view of the downloads state (T-0507). */
class DownloadsDataTest {
    @Test
    fun anUpdateIsAnInstalledRegionWhoseCatalogueVersionDiffers() {
        val data =
            data(
                catalog = listOf(entry("a", "2"), entry("b", "2"), entry("c", "2")),
                installed = listOf(InstalledPack("a", "1"), InstalledPack("b", "2")),
            )

        assertEquals(listOf("a"), data.updates.map { it.id })
    }

    @Test
    fun withoutACatalogueThereIsNothingToUpdate() {
        assertTrue(DownloadsData(installed = listOf(InstalledPack("a", "1"))).updates.isEmpty())
    }

    @Test
    fun twoRoutingBuildsAmongTheInstalledPacksAreMixed() {
        val mixed = data(installed = listOf(InstalledPack("a", "1", build = "x"), InstalledPack("b", "1", build = "y")))
        val same = data(installed = listOf(InstalledPack("a", "1", build = "x"), InstalledPack("b", "1", build = "x")))
        val unknown = data(installed = listOf(InstalledPack("a", "1", build = "x"), InstalledPack("b", "1")))

        assertTrue(mixed.hasMixedBuilds)
        assertFalse(same.hasMixedBuilds)
        assertFalse(unknown.hasMixedBuilds)
    }

    @Test
    fun anAddonDownloadsUnderItsOwnPackageId() {
        val addon = CatalogAddon(id = "elevation-30m", version = "2026-01", bytes = 30)
        val catalog = RegionCatalog(format = 1, regions = listOf(entry("ua-46", "2").copy(addons = listOf(addon))))

        assertEquals(CatalogDownload("ua-46", "2", 1), catalog.download("ua-46"))
        assertEquals(
            CatalogDownload("ua-46-elevation-30m", "2026-01", 30, addonOf = "ua-46"),
            catalog.download("ua-46-elevation-30m"),
        )
        assertEquals(null, catalog.download("ua-32-elevation-30m"))
    }

    @Test
    fun anAddonOfAnUnknownKindHasNoDownload() {
        val mystery = CatalogAddon(id = "mystery-pack", version = "2026-01", bytes = 30)
        val catalog = RegionCatalog(format = 1, regions = listOf(entry("ua-46", "2").copy(addons = listOf(mystery))))

        assertEquals(null, catalog.download("ua-46-mystery-pack"))
    }

    /**
     * The elevation reader is the only one that opens a pack once per process:
     * the map, the search index and the routing engine all follow the store in
     * this process, so their packs never raise the hint (T-0626).
     */
    @Test
    fun onlyTheSourcePackOfTheElevationReaderWaitsForTheNextStart() {
        val mounted = listOf(pack("a", "1", CatalogPartKind.MAP, CatalogPartKind.ELEVATION))

        assertFalse(DownloadsData(installed = mounted).appliesAtNextStart)
        assertFalse(DownloadsData(installed = mounted, mountedPacks = mounted).appliesAtNextStart)
        assertTrue(
            DownloadsData(
                installed = listOf(pack("a", "2", CatalogPartKind.MAP, CatalogPartKind.ELEVATION)),
                mountedPacks = mounted,
            ).appliesAtNextStart,
        )
        assertFalse(DownloadsData(installed = emptyList(), mountedPacks = mounted).appliesAtNextStart)
    }

    @Test
    fun anUpdatedPackThatIsNotTheElevationSourceDoesNotWaitForTheNextStart() {
        val mounted =
            listOf(
                pack("a", "1", CatalogPartKind.MAP, CatalogPartKind.ELEVATION),
                pack("b", "1", CatalogPartKind.MAP, CatalogPartKind.ELEVATION),
            )
        val updated =
            listOf(
                pack("a", "1", CatalogPartKind.MAP, CatalogPartKind.ELEVATION),
                pack("b", "2", CatalogPartKind.MAP, CatalogPartKind.ELEVATION),
            )

        assertFalse(DownloadsData(installed = updated, mountedPacks = mounted).appliesAtNextStart)
    }

    @Test
    fun aPackWithoutElevationDoesNotWaitForTheNextStart() {
        val mounted = listOf(pack("world", "1", CatalogPartKind.MAP))

        assertFalse(
            DownloadsData(installed = listOf(pack("world", "2", CatalogPartKind.MAP)), mountedPacks = mounted).appliesAtNextStart,
        )
    }

    @Test
    fun theElevationAddonOfTheSourcePackWaitsForTheNextStart() {
        val mounted = listOf(pack("a", "1", CatalogPartKind.MAP, CatalogPartKind.ELEVATION))

        assertTrue(
            DownloadsData(
                installed = listOf(pack("a", "1", CatalogPartKind.MAP, CatalogPartKind.ELEVATION, CatalogPartKind.ELEVATION_30M)),
                mountedPacks = mounted,
            ).appliesAtNextStart,
        )
        assertTrue(
            DownloadsData(
                installed = listOf(pack("a", "2", CatalogPartKind.MAP, CatalogPartKind.ELEVATION)),
                mountedPacks = listOf(pack("a", "1", CatalogPartKind.MAP)),
            ).appliesAtNextStart,
        )
    }

    @Test
    fun aNewFirstRegionWithElevationWaitsForTheNextStart() {
        val mounted = listOf(pack("b", "1", CatalogPartKind.MAP, CatalogPartKind.ELEVATION))
        val newFirst =
            listOf(
                pack("a", "1", CatalogPartKind.MAP, CatalogPartKind.ELEVATION),
                pack("b", "1", CatalogPartKind.MAP, CatalogPartKind.ELEVATION),
            )

        assertTrue(DownloadsData(installed = newFirst, mountedPacks = mounted).appliesAtNextStart)
        assertFalse(
            DownloadsData(
                installed = listOf(pack("a", "1", CatalogPartKind.MAP)) + mounted,
                mountedPacks = mounted,
            ).appliesAtNextStart,
        )
    }

    @Test
    fun aTransferShareScalesArchiveBytesToTheWholeAndNeverPassesIt() {
        val half = DownloadTransfer("a", DownloadTransferState.DOWNLOADING, bytesDone = 34, bytesTotal = 68)
        val over = half.copy(bytesDone = 90)
        val unknown = half.copy(bytesTotal = 0)

        assertEquals(50L, half.fractionOf(100))
        assertEquals(100L, over.fractionOf(100))
        assertEquals(0L, unknown.fractionOf(100))
    }

    private fun pack(
        region: String,
        version: String,
        vararg parts: CatalogPartKind,
    ) = InstalledPack(region, version, parts = parts.toList())

    private fun data(
        catalog: List<CatalogRegion> = emptyList(),
        installed: List<InstalledPack> = emptyList(),
    ) = DownloadsData(catalog = RegionCatalog(format = 1, regions = catalog), installed = installed)

    private fun entry(
        id: String,
        version: String,
    ) = CatalogRegion(id = id, version = version, bytes = 1)
}
