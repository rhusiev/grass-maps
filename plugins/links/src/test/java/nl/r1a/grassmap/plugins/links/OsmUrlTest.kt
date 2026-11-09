package nl.r1a.grassmap.plugins.links

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.OsmType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The osm.org element, short-link and permalink bodies (T-0211). */
class OsmUrlTest {
    @Test
    fun everyElementTypeIsNamed() {
        assertEquals(
            LinkResult.Success(LinkTarget.Element(OsmType.NODE, 1L)),
            LinkRouter.route("https://www.openstreetmap.org/node/1"),
        )
        assertEquals(
            LinkResult.Success(LinkTarget.Element(OsmType.WAY, 123_456L)),
            LinkRouter.route("https://www.openstreetmap.org/way/123456"),
        )
        assertEquals(
            LinkResult.Success(LinkTarget.Element(OsmType.RELATION, 98_765L)),
            LinkRouter.route("https://www.openstreetmap.org/relation/98765"),
        )
    }

    @Test
    fun theHostMayOmitWwwAndUseOsmOrg() {
        assertEquals(
            LinkResult.Success(LinkTarget.Element(OsmType.NODE, 7L)),
            LinkRouter.route("http://osm.org/node/7"),
        )
        assertEquals(
            LinkResult.Success(LinkTarget.Element(OsmType.NODE, 7L)),
            LinkRouter.route("https://openstreetmap.org/node/7"),
        )
        assertEquals(
            LinkResult.Success(LinkTarget.Element(OsmType.NODE, 7L)),
            LinkRouter.route("https://www.osm.org/node/7"),
        )
    }

    @Test
    fun theSchemeAndHostAreCaseInsensitive() {
        assertEquals(
            LinkResult.Success(LinkTarget.Element(OsmType.NODE, 7L)),
            LinkRouter.route("HTTPS://WWW.OpenStreetMap.org/node/7"),
        )
    }

    @Test
    fun thePageQueryIsAcceptedAndNotRead() {
        assertEquals(
            LinkResult.Success(LinkTarget.Point(GeoPoint(51.510772705078125, 0.054931640625), 9.0)),
            LinkRouter.route("https://osm.org/go/0EEQjE--?m"),
        )
        assertEquals(
            LinkResult.Success(LinkTarget.Element(OsmType.NODE, 7L)),
            LinkRouter.route("https://www.openstreetmap.org/node/7?layers=T"),
        )
    }

    @Test
    fun aShortLinkPathIsADecodedPoint() {
        assertEquals(
            LinkResult.Success(LinkTarget.Point(GeoPoint(51.510998010635376, 0.05499601364135742), 16.0)),
            LinkRouter.route("https://osm.org/go/0EEQjEEb"),
        )
    }

    @Test
    fun anElementWithAMapFragmentCarriesTheView() {
        assertEquals(
            LinkResult.Success(
                LinkTarget.Element(OsmType.RELATION, 98_765L, GeoPoint(49.84, 24.03), 12.5),
            ),
            LinkRouter.route("https://www.openstreetmap.org/relation/98765#map=12.5/49.84/24.03"),
        )
    }

    @Test
    fun aBrokenMapFragmentLeavesTheElementWithoutAPoint() {
        assertEquals(
            LinkResult.Success(LinkTarget.Element(OsmType.NODE, 7L)),
            LinkRouter.route("https://www.openstreetmap.org/node/7#map=not-a-view"),
        )
    }

    @Test
    fun aRootPermalinkIsAPosition() {
        assertEquals(
            LinkResult.Success(LinkTarget.Point(GeoPoint(49.84, 24.03), 17.0)),
            LinkRouter.route("https://www.openstreetmap.org/#map=17/49.84/24.03"),
        )
    }

    @Test
    fun aCurrentPermalinkMayNameALayerCodeAfterTheView() {
        assertEquals(
            LinkResult.Success(LinkTarget.Point(GeoPoint(56.8139, -5.0650), 14.0)),
            LinkRouter.route("https://www.openstreetmap.org/#map=14/56.8139/-5.0650&layers=C"),
        )
    }

    @Test
    fun thePre2013PermalinkQueryIsAPosition() {
        assertEquals(
            LinkResult.Success(LinkTarget.Point(GeoPoint(51.457142, -0.20971), 18.0)),
            LinkRouter.route("https://www.openstreetmap.org/?lat=51.457142&lon=-0.20971&zoom=18&layers=M"),
        )
    }

    @Test
    fun aLegacyQueryWithoutAZoomIsUnsupported() {
        assertEquals(
            LinkResult.Failure(LinkFailure.UNSUPPORTED),
            LinkRouter.route("https://www.openstreetmap.org/?lat=51.45&lon=-0.2"),
        )
    }

    @Test
    fun aLegacyQueryWithABadValueIsMalformed() {
        for (
        uri in
        listOf(
            "https://www.openstreetmap.org/?lat=abc&lon=-0.2&zoom=18",
            "https://www.openstreetmap.org/?lat=95&lon=-0.2&zoom=18",
            "https://www.openstreetmap.org/?lat=51.45&lon=-0.2&zoom=99",
        )
        ) {
            assertEquals(uri, LinkResult.Failure(LinkFailure.MALFORMED), LinkRouter.route(uri))
        }
    }

    @Test
    fun aRootUrlWithoutAViewIsUnsupported() {
        assertEquals(
            LinkResult.Failure(LinkFailure.UNSUPPORTED),
            LinkRouter.route("https://www.openstreetmap.org/"),
        )
    }

    @Test
    fun aNonPositiveElementIdIsMalformed() {
        for (uri in listOf("https://www.openstreetmap.org/node/0", "https://www.openstreetmap.org/way/-3")) {
            assertEquals(uri, LinkResult.Failure(LinkFailure.MALFORMED), LinkRouter.route(uri))
        }
    }

    @Test
    fun aSubPageOrADeeperPathIsUnsupported() {
        for (
        uri in
        listOf(
            "https://www.openstreetmap.org/node/1/history",
            "https://www.openstreetmap.org/node",
            "https://www.openstreetmap.org/directions?from=1",
        )
        ) {
            assertEquals(uri, LinkResult.Failure(LinkFailure.UNSUPPORTED), LinkRouter.route(uri))
        }
    }

    @Test
    fun aLinkWithCredentialsOrWithoutTheAuthorityIsRefused() {
        assertEquals(
            LinkResult.Failure(LinkFailure.UNSUPPORTED),
            LinkRouter.route("https://user@www.openstreetmap.org/node/1"),
        )
        assertEquals(LinkResult.Failure(LinkFailure.MALFORMED), LinkRouter.route("https:www.openstreetmap.org/node/1"))
    }
}
