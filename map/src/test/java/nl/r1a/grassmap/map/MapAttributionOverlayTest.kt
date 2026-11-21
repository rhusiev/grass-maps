package nl.r1a.grassmaps.map

import nl.r1a.grassmaps.plugin.api.model.MapCredits
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
    fun anEngineCreditNamingTheCopernicusDemOrGlo30CollapsesIntoTheCopernicusEntry() {
        val credits =
            mergeCredits(
                sourceCredits = listOf(MapCredits.COPERNICUS_DEM),
                engineCredits = listOf("Copernicus DEM GLO-30", "GLO-30 DEM"),
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

    @Test
    fun aCreditThatOnlyNamesCopernicusDataIsNotTheDemCredit() {
        assertEquals(listOf("Contains Copernicus data"), mergeCredits(emptyList(), listOf("Contains Copernicus data")))
    }

    @Test
    fun anEoxCreditStaysWholeBesideTheOsmAndDemCredits() {
        val credits =
            mergeCredits(
                sourceCredits = listOf(MapCredits.OPENSTREETMAP, MapCredits.COPERNICUS_DEM),
                engineCredits = listOf(MapCredits.EOX_2016, "Copernicus DEM GLO-30"),
            )

        assertEquals(listOf(MapCredits.OPENSTREETMAP, MapCredits.COPERNICUS_DEM, MapCredits.EOX_2016), credits)
        assertEquals(
            "EOxCloudless <a href=\"https://cloudless.eox.at\">https://cloudless.eox.at</a> by EOX IT Services " +
                "GmbH (Contains modified Copernicus Sentinel data 2016 &amp; 2017)",
            credits.last(),
        )
        assertEquals(
            "\u00A9 OpenStreetMap contributors \u00B7 Copernicus \u00B7 EOxCloudless",
            collapsedLabel(credits),
        )
    }
}
