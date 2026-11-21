package nl.r1a.grassmaps.plugins.downloads.ui

import androidx.compose.ui.geometry.Offset
import nl.r1a.grassmaps.plugin.api.model.CatalogPartKind
import nl.r1a.grassmaps.plugin.api.model.DownloadsData
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.InstalledPack
import nl.r1a.grassmaps.plugin.api.model.RegionOutline
import nl.r1a.grassmaps.plugin.api.testing.fixture.CatalogFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search by name, the "near me" row and the tap-to-select mini-map of T-0506. */
class DownloadsSearchAndNearMeTest {
    private val data = DownloadsData(catalog = CatalogTestData.real(), serverUrl = "https://maps.example")

    @Test
    fun ukrainianAndEnglishNamesBothFindTernopilOblast() {
        listOf("Тернопіль", "Ternopil", "  ternopil ", "ТЕРНОПІЛЬ", "Ternopil Oblast").forEach { query ->
            val state = DownloadsScreen.state(data, query = query)

            assertEquals(query, listOf(TERNOPIL), state.searchResults.map { it.region })
        }
    }

    @Test
    fun aBlankOrUnknownQueryFindsNothing() {
        assertTrue(DownloadsScreen.state(data, query = "").searchResults.isEmpty())
        assertTrue(DownloadsScreen.state(data, query = "   ").searchResults.isEmpty())
        assertTrue(DownloadsScreen.state(data, query = "Atlantis").searchResults.isEmpty())
    }

    @Test
    fun nearMeIsTheDetectedOblastWhileItIsNotInstalled() {
        val elsewhere = InstalledPack(TERNOPIL, CatalogFixture.VERSION, parts = listOf(CatalogPartKind.MAP))

        val state = DownloadsScreen.state(data.copy(installed = listOf(elsewhere)), detectedRegion = CatalogFixture.LVIV)

        assertEquals(CatalogFixture.LVIV, state.nearMe?.region)
    }

    @Test
    fun nearMeIsEmptyWithoutAFixOrOnceTheOblastIsInstalled() {
        assertNull(DownloadsScreen.state(data).nearMe)
        val installed = InstalledPack(CatalogFixture.LVIV, CatalogFixture.VERSION, parts = listOf(CatalogPartKind.MAP))

        val state = DownloadsScreen.state(data.copy(installed = listOf(installed)), detectedRegion = CatalogFixture.LVIV)

        assertNull(state.nearMe)
    }

    @Test
    fun nearMeIsEmptyWhileTheFirstRunOfferAlreadyNamesTheOblast() {
        val withWorld = data.copy(catalog = CatalogTestData.withWorld())

        val state = DownloadsScreen.state(withWorld, detectedRegion = CatalogFixture.LVIV)

        assertTrue(state.firstRun.any { it.region == CatalogFixture.LVIV })
        assertNull(state.nearMe)
    }

    @Test
    fun aTapOnABorderSelectsItsRegionAndATapBetweenBordersSelectsNone() {
        val outlines = listOf(square("a", 0.0), square("b", 2.0))
        val projection = MiniMapProjection(outlines, width = 300f, height = 100f)

        assertEquals("a", projection.regionAt(outlines, projection.toScreen(GeoPoint(0.5, 0.5))))
        assertEquals("b", projection.regionAt(outlines, projection.toScreen(GeoPoint(0.5, 2.5))))
        assertNull(projection.regionAt(outlines, projection.toScreen(GeoPoint(0.5, 1.5))))
        assertNull(projection.regionAt(outlines, Offset(-50f, -50f)))
    }

    @Test
    fun withoutBordersNothingCanBeTapped() {
        val projection = MiniMapProjection(emptyList(), width = 300f, height = 100f)

        assertTrue(projection.isEmpty)
        assertNull(projection.regionAt(emptyList(), Offset(10f, 10f)))
    }

    private fun square(
        region: String,
        west: Double,
    ): RegionOutline =
        RegionOutline(
            region,
            listOf(
                listOf(
                    GeoPoint(0.0, west),
                    GeoPoint(0.0, west + 1.0),
                    GeoPoint(1.0, west + 1.0),
                    GeoPoint(1.0, west),
                    GeoPoint(0.0, west),
                ),
            ),
        )

    private companion object {
        const val TERNOPIL = "ua-61"
    }
}
