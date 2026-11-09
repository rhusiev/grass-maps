package nl.r1a.grassmap.plugins.links

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.OsmType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure route decision for an intent URI (T-0211).
 *
 * Each case is what `am start -a android.intent.action.VIEW -d <uri>` hands to
 * the Activity, so the test pins both the accepted families and the honest
 * answer for everything else.
 */
class LinkRouterTest {
    @Test
    fun aGeoUriCentresOnItsCoordinatePairAtStreetZoom() {
        assertEquals(
            LinkResult.Success(LinkTarget.Point(GeoPoint(49.821740, 24.023910), 16.0)),
            LinkRouter.route("geo:49.821740,24.023910"),
        )
    }

    @Test
    fun aGeoUriOfAMapLinkCarriesItsPoint() {
        assertEquals(
            LinkResult.Success(LinkTarget.Point(GeoPoint(50.4501, 30.5234), 16.0)),
            LinkRouter.route("geo:50.4501,30.5234;u=150"),
        )
    }

    @Test
    fun aRootOsmPermalinkCarriesItsFragmentView() {
        assertEquals(
            LinkResult.Success(LinkTarget.Point(GeoPoint(51.5110, 0.0550), 9.0)),
            LinkRouter.route("https://www.openstreetmap.org/#map=9/51.5110/0.0550"),
        )
    }

    @Test
    fun anOsmElementUrlWithoutCoordinatesNamesOnlyTheElement() {
        assertEquals(
            LinkResult.Success(LinkTarget.Element(OsmType.NODE, 4_815_162_342L)),
            LinkRouter.route("https://www.openstreetmap.org/node/4815162342"),
        )
    }

    @Test
    fun anOsmElementUrlWithAMapFragmentCarriesBoth() {
        assertEquals(
            LinkResult.Success(
                LinkTarget.Element(OsmType.WAY, 123L, GeoPoint(49.84, 24.03), 17.0),
            ),
            LinkRouter.route("https://osm.org/way/123#map=17/49.84/24.03"),
        )
    }

    @Test
    fun anOsmShortLinkIsAPointWithTheZoomItEncodes() {
        assertEquals(
            LinkResult.Success(
                LinkTarget.Point(
                    GeoPoint(51.510772705078125, 0.054931640625),
                    9.0,
                ),
            ),
            LinkRouter.route("https://osm.org/go/0EEQjE--"),
        )
    }

    @Test
    fun anUnknownSchemeIsUnsupportedNotMalformed() {
        assertEquals(
            LinkResult.Failure(LinkFailure.UNSUPPORTED),
            LinkRouter.route("mailto:someone@example.com"),
        )
        assertEquals(LinkResult.Failure(LinkFailure.UNSUPPORTED), LinkRouter.route("tel:+380441234567"))
    }

    @Test
    fun anotherHostIsUnsupported() {
        assertEquals(
            LinkResult.Failure(LinkFailure.UNSUPPORTED),
            LinkRouter.route("https://example.com/node/1"),
        )
    }

    @Test
    fun aPageThisAppDoesNotOpenIsUnsupported() {
        for (uri in listOf("https://www.openstreetmap.org/search?query=Lviv", "https://www.openstreetmap.org/changeset/1")) {
            assertEquals(uri, LinkResult.Failure(LinkFailure.UNSUPPORTED), LinkRouter.route(uri))
        }
    }

    @Test
    fun anEmptyOrSchemelessLinkIsMalformed() {
        for (uri in listOf(null, "", "   ", "not a uri", "geo", "/node/1", ":geo:1,2")) {
            assertEquals("$uri", LinkResult.Failure(LinkFailure.MALFORMED), LinkRouter.route(uri))
        }
    }

    @Test
    fun aMalformedGeoBodyIsMalformed() {
        for (uri in listOf("geo:", "geo:abc", "geo:1", "geo:1,2,3,4", "geo:NaN,0", "geo:1e400,0", "geo:0x10,0")) {
            assertEquals("$uri", LinkResult.Failure(LinkFailure.MALFORMED), LinkRouter.route(uri))
        }
    }

    @Test
    fun aMalformedElementUrlIsMalformed() {
        assertEquals(
            LinkResult.Failure(LinkFailure.MALFORMED),
            LinkRouter.route("https://www.openstreetmap.org/node/abc"),
        )
        assertEquals(
            LinkResult.Failure(LinkFailure.MALFORMED),
            LinkRouter.route("https://www.openstreetmap.org/node/0"),
        )
        assertEquals(
            LinkResult.Failure(LinkFailure.MALFORMED),
            LinkRouter.route("https://osm.org/go/"),
        )
    }

    @Test
    fun aCoordinateOutsideItsRangeIsOutOfRange() {
        for (uri in listOf("geo:91,0", "geo:-91,0", "geo:0,181", "geo:0,-181")) {
            assertEquals("$uri", LinkResult.Failure(LinkFailure.OUT_OF_RANGE), LinkRouter.route(uri))
        }
    }

    @Test
    fun aBadMapFragmentOnARootPermalinkIsMalformed() {
        for (uri in listOf("https://www.openstreetmap.org/#map=9/51.5", "https://www.openstreetmap.org/#map=9/95/0")) {
            assertEquals("$uri", LinkResult.Failure(LinkFailure.MALFORMED), LinkRouter.route(uri))
        }
    }
}
