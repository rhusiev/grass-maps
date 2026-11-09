package nl.r1a.grassmap.plugins.links

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/** The `geo:` body parser, RFC 5870 (T-0211). */
class GeoUriTest {
    @Test
    fun thePairIsLatitudeThenLongitude() {
        assertEquals(
            LinkResult.Success(LinkTarget.Point(GeoPoint(49.821740, 24.023910), LinkRouter.DEFAULT_POINT_ZOOM)),
            LinkRouter.route("geo:49.821740,24.023910"),
        )
        assertEquals(
            LinkResult.Success(LinkTarget.Point(GeoPoint(-33.8688, -70.6483), LinkRouter.DEFAULT_POINT_ZOOM)),
            LinkRouter.route("geo:-33.8688,-70.6483"),
        )
    }

    @Test
    fun aQueryThatNamesThePairAgainSuppliesItsLabel() {
        val result = LinkRouter.route("geo:49.821740,24.023910?q=49.821740,24.023910(Lviv%20Rynok)")

        assertEquals(
            LinkResult.Success(
                LinkTarget.Point(GeoPoint(49.821740, 24.023910), LinkRouter.DEFAULT_POINT_ZOOM, "Lviv Rynok"),
            ),
            result,
        )
    }

    @Test
    fun aFreeTextQueryBecomesTheLabel() {
        assertEquals(
            LinkResult.Success(
                LinkTarget.Point(GeoPoint(50.45, 30.52), LinkRouter.DEFAULT_POINT_ZOOM, "Kyiv"),
            ),
            LinkRouter.route("geo:50.45,30.52?q=Kyiv"),
        )
    }

    @Test
    fun aLabelIsPercentDecodedAsUtf8() {
        assertEquals(
            LinkResult.Success(
                LinkTarget.Point(GeoPoint(49.84, 24.03), LinkRouter.DEFAULT_POINT_ZOOM, "Львів"),
            ),
            LinkRouter.route("geo:49.84,24.03?q=49.84,24.03(%D0%9B%D1%8C%D0%B2%D1%96%D0%B2)"),
        )
    }

    @Test
    fun anAltitudeIsAcceptedAndNotUsed() {
        assertEquals(
            LinkResult.Success(LinkTarget.Point(GeoPoint(49.84, 24.03), LinkRouter.DEFAULT_POINT_ZOOM)),
            LinkRouter.route("geo:49.84,24.03,320"),
        )
    }

    @Test
    fun theRfcParametersAfterASemicolonAreAcceptedAndNotUsed() {
        assertEquals(
            LinkResult.Success(LinkTarget.Point(GeoPoint(49.84, 24.03), LinkRouter.DEFAULT_POINT_ZOOM)),
            LinkRouter.route("geo:49.84,24.03;crs=wgs84;u=35"),
        )
    }

    @Test
    fun theSchemeIsCaseInsensitive() {
        assertEquals(
            LinkResult.Success(LinkTarget.Point(GeoPoint(49.84, 24.03), LinkRouter.DEFAULT_POINT_ZOOM)),
            LinkRouter.route("GEO:49.84,24.03"),
        )
    }

    @Test
    fun anEmptyQueryHasNoLabel() {
        assertEquals(
            LinkResult.Success(LinkTarget.Point(GeoPoint(49.84, 24.03), LinkRouter.DEFAULT_POINT_ZOOM)),
            LinkRouter.route("geo:49.84,24.03?q="),
        )
    }

    @Test
    fun anUnescapedPercentIsKeptInTheLabelInsteadOfFailing() {
        assertEquals(
            LinkResult.Success(
                LinkTarget.Point(GeoPoint(49.84, 24.03), LinkRouter.DEFAULT_POINT_ZOOM, "100% lane"),
            ),
            LinkRouter.route("geo:49.84,24.03?q=100% lane"),
        )
    }

    @Test
    fun aThirdExtraFieldOrAMissingPairIsMalformed() {
        for (body in listOf("", "49.84", "49.84,24.03,1,2", ",24.03", "49.84,")) {
            assertEquals(body, LinkResult.Failure(LinkFailure.MALFORMED), GeoUri.parse(body))
        }
    }

    @Test
    fun aNonDecimalNumberIsMalformed() {
        for (body in listOf("49.84d,24.03", "1.5f,2", "4 9,24", "0x1p3,24")) {
            assertEquals(body, LinkResult.Failure(LinkFailure.MALFORMED), GeoUri.parse(body))
        }
    }

    @Test
    fun aCoordinateOutsideItsRangeIsOutOfRange() {
        for (body in listOf("90.0001,0", "-90.0001,0", "0,180.0001", "0,-180.0001")) {
            assertEquals(body, LinkResult.Failure(LinkFailure.OUT_OF_RANGE), GeoUri.parse(body))
        }
        assertEquals(
            LinkResult.Success(LinkTarget.Point(GeoPoint(90.0, 180.0), LinkRouter.DEFAULT_POINT_ZOOM)),
            GeoUri.parse("90,180"),
        )
    }
}
