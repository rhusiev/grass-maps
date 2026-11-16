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

    private fun data(
        catalog: List<CatalogRegion> = emptyList(),
        installed: List<InstalledPack> = emptyList(),
    ) = DownloadsData(catalog = RegionCatalog(format = 1, regions = catalog), installed = installed)

    private fun entry(
        id: String,
        version: String,
    ) = CatalogRegion(id = id, version = version, bytes = 1)
}
