package nl.r1a.grassmaps.map.style

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.RegionOutline
import org.junit.Assert.assertEquals
import org.junit.Test

/** The GeoJSON the map draws for the highlighted regions, asserted as a document. */
class RegionHighlightsTest {
    @Test
    fun aRegionBecomesOneMultiPolygonWithAClosedRing() {
        val region =
            RegionOutline(
                region = "ua-61",
                polygons = listOf(listOf(GeoPoint(49.0, 25.0), GeoPoint(49.0, 26.0), GeoPoint(50.0, 25.5))),
            )

        assertEquals(
            """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"region":"ua-61"},""" +
                """"geometry":{"type":"MultiPolygon","coordinates":[[[[25.0,49.0],[26.0,49.0],[25.5,50.0],[25.0,49.0]]]]}}]}""",
            RegionHighlightGeoJson.featureCollection(listOf(region)),
        )
    }

    @Test
    fun aRingThatIsAlreadyClosedIsNotClosedTwice() {
        val ring = listOf(GeoPoint(1.0, 2.0), GeoPoint(1.0, 3.0), GeoPoint(2.0, 3.0), GeoPoint(1.0, 2.0))

        val json = RegionHighlightGeoJson.featureCollection(listOf(RegionOutline("ua-01", listOf(ring))))

        assertEquals("[[[2.0,1.0],[3.0,1.0],[3.0,2.0],[2.0,1.0]]]", json.substringAfter("coordinates\":[").substringBefore("]}}"))
    }

    @Test
    fun aRegionWithNoRingIsLeftOut() {
        assertEquals(
            """{"type":"FeatureCollection","features":[]}""",
            RegionHighlightGeoJson.featureCollection(listOf(RegionOutline("ua-01", emptyList()))),
        )
    }
}
