package nl.r1a.grassmap.plugin.api.model

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

    @Test
    fun anInstalledPackNewerThanTheMountedOneAppliesAtTheNextStart() {
        val mounted = listOf(InstalledPack("a", "1", parts = listOf(CatalogPartKind.MAP)))

        assertFalse(DownloadsData(installed = mounted).appliesAtNextStart)
        assertFalse(DownloadsData(installed = mounted, mountedPacks = mounted).appliesAtNextStart)
        assertTrue(
            DownloadsData(installed = listOf(InstalledPack("a", "2")), mountedPacks = mounted).appliesAtNextStart,
        )
        assertTrue(
            DownloadsData(
                installed = listOf(InstalledPack("a", "1", parts = listOf(CatalogPartKind.MAP, CatalogPartKind.ELEVATION_30M))),
                mountedPacks = mounted,
            ).appliesAtNextStart,
        )
        assertTrue(DownloadsData(installed = mounted + InstalledPack("b", "1"), mountedPacks = mounted).appliesAtNextStart)
        assertFalse(DownloadsData(installed = emptyList(), mountedPacks = mounted).appliesAtNextStart)
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

    private fun data(
        catalog: List<CatalogRegion> = emptyList(),
        installed: List<InstalledPack> = emptyList(),
    ) = DownloadsData(catalog = RegionCatalog(format = 1, regions = catalog), installed = installed)

    private fun entry(
        id: String,
        version: String,
    ) = CatalogRegion(id = id, version = version, bytes = 1)
}
