package nl.r1a.grassmap.map

import nl.r1a.grassmap.plugin.api.model.MapCredits
import org.junit.Assert.assertEquals
import org.junit.Test

class MapAttributionOverlayTest {
    @Test
    fun worldAndRegionShareOneOpenStreetMapCredit() {
        val credits = mergeCredits(listOf(MapCredits.OPENSTREETMAP, MapCredits.OPENSTREETMAP), emptyList())

        assertEquals(listOf(MapCredits.OPENSTREETMAP), credits)
    }

    @Test
    fun theOnlineOsmRasterAddsNoSecondOpenStreetMapCredit() {
        val credits =
            mergeCredits(
                sourceCredits = listOf(MapCredits.OPENSTREETMAP),
                engineCredits = listOf(MapCredits.OPENSTREETMAP, MapCredits.OPENSTREETMAP),
            )

        assertEquals(listOf(MapCredits.OPENSTREETMAP), credits)
    }

    @Test
    fun differentCreditsCombineInOrder() {
        val credits =
            mergeCredits(
                sourceCredits = listOf(MapCredits.OPENSTREETMAP, MapCredits.COPERNICUS_DEM),
                engineCredits = listOf("Other tiles"),
            )

        assertEquals(listOf(MapCredits.OPENSTREETMAP, MapCredits.COPERNICUS_DEM, "Other tiles"), credits)
    }

    @Test
    fun blankCreditsAreDropped() {
        assertEquals(emptyList<String>(), mergeCredits(listOf(" "), listOf("")))
    }
}
