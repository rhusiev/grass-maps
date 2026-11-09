package nl.r1a.grassmap.plugins.share

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.OsmType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The osm.org and `geo:` builders (T-0210). */
class ShareLinksTest {
    @Test
    fun anOsmElementUrlNamesTheTypeAndTheId() {
        assertEquals(
            "https://www.openstreetmap.org/node/4815162342",
            ShareLinks.osmUrl(OsmType.NODE, 4_815_162_342L),
        )
        assertEquals("https://www.openstreetmap.org/way/123456", ShareLinks.osmUrl(OsmType.WAY, 123_456L))
        assertEquals("https://www.openstreetmap.org/relation/98765", ShareLinks.osmUrl(OsmType.RELATION, 98_765L))
    }

    @Test
    fun aGeoUriCarriesThePointAndAnOptionalEncodedLabel() {
        val point = GeoPoint(49.821740, 24.023910)

        assertEquals("geo:49.821740,24.023910", ShareLinks.geoUri(point))
        assertEquals(
            "geo:49.821740,24.023910?q=49.821740,24.023910(Lviv%20Rynok)",
            ShareLinks.geoUri(point, "Lviv Rynok"),
        )
        assertEquals(
            "geo:49.821740,24.023910?q=49.821740,24.023910(A%28B%29)",
            ShareLinks.geoUri(point, "A(B)"),
        )
    }

    @Test
    fun aPlaceWithAnIdentitySharesTheOsmElement() {
        val link = ShareLinks.placeLink(PlaceIdentity(OsmType.NODE, 42L), GeoPoint(49.0, 24.0))

        assertTrue(link is PlaceLink.Osm)
        assertEquals("https://www.openstreetmap.org/node/42", link.uri)
    }

    @Test
    fun aPlaceWithoutAnIdentitySharesTheGeoUriInstead() {
        val link = ShareLinks.placeLink(null, GeoPoint(49.821740, 24.023910))

        assertTrue(link is PlaceLink.Geo)
        assertEquals("geo:49.821740,24.023910", link.uri)
        assertTrue("openstreetmap.org" !in link.uri)
    }

    @Test
    fun anIdentityNeedsBothATypeAndAPositiveId() {
        assertEquals(PlaceIdentity(OsmType.WAY, 7L), ShareLinks.identityOf(OsmType.WAY, 7L))
        assertNull(ShareLinks.identityOf(null, 7L))
        assertNull(ShareLinks.identityOf(OsmType.WAY, null))
        assertNull(ShareLinks.identityOf(OsmType.WAY, 0L))
        assertNull(ShareLinks.identityOf(OsmType.WAY, -1L))
    }
}
