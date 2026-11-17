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

    @Test
    fun anEngineCreditNamingOpenStreetMapCollapsesIntoTheOneEntry() {
        val credits =
            mergeCredits(
                sourceCredits = listOf(MapCredits.OPENSTREETMAP),
                engineCredits = listOf("&copy; <a href=\"https://osm.org\">OpenStreetMap</a> contributors", "(c) openstreetmap"),
            )

        assertEquals(listOf(MapCredits.OPENSTREETMAP), credits)
    }

    @Test
    fun anEngineCreditNamingCopernicusOrGlo30CollapsesIntoTheCopernicusEntry() {
        val credits =
            mergeCredits(
                sourceCredits = listOf(MapCredits.COPERNICUS_DEM),
                engineCredits = listOf("Contains Copernicus data", "Copernicus DEM GLO-30", "GLO-30 DEM"),
            )

        assertEquals(listOf(MapCredits.COPERNICUS_DEM), credits)
    }

    @Test
    fun anUnrelatedEngineCreditIsKeptAsItIs() {
        assertEquals(listOf("Other tiles"), mergeCredits(emptyList(), listOf("Other tiles")))
    }

    @Test
    fun theCollapsedLineIsShortWithAndWithoutContours() {
        assertEquals("\u00A9 OpenStreetMap contributors", collapsedLabel(listOf(MapCredits.OPENSTREETMAP)))
        assertEquals(
            "\u00A9 OpenStreetMap contributors \u00B7 Copernicus",
            collapsedLabel(listOf(MapCredits.OPENSTREETMAP, MapCredits.COPERNICUS_DEM)),
        )
    }

    @Test
    fun theCollapsedLineStripsMarkupFromOtherCredits() {
        assertEquals("Tiles by Acme", collapsedLabel(listOf("Tiles by <a href=\"https://x.test\">Acme</a>")))
    }
}
