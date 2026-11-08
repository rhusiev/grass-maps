package nl.r1a.grassmap.plugins.point.info

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The inverse geodesic against GeographicLib reference values (T-0112).
 *
 * Every reference below was produced by `geographiclib.geodesic.Geodesic.WGS84
 * .Inverse(lat1, lon1, lat2, lon2)`, whose `s12` is the distance in metres and
 * `azi1` the initial bearing in degrees. The cases cover the mock-walk first
 * fix, the equator, a mid-latitude east-west line, the antimeridian, a
 * near-pole pair, the southern hemisphere and a long diagonal.
 *
 * The acceptance tolerance is 0.5 % and 1 degree; these tests hold the code to
 * a tolerance thousands of times tighter, so the acceptance bound cannot pass
 * on a wrong formula.
 */
class GeodesicTest {
    @Test
    fun theMockFixToALongPressedPointMatchesGeographicLib() {
        val result = Geodesic.inverse(MOCK_FIX, GeoPoint(latitude = 49.8500, longitude = 24.0400))

        assertEquals(1_084.755_494, result.distanceMeters, MILLIMETRE)
        assertEquals(33.842_680, result.azimuthDegrees, MICRO_DEGREE)
    }

    @Test
    fun everyReferenceVectorIsWithinAMillimetreAndAMicrodegree() {
        for (vector in VECTORS) {
            val result = Geodesic.inverse(vector.from, vector.to)

            assertEquals(vector.name, vector.distanceMeters, result.distanceMeters, MILLIMETRE + vector.distanceMeters * 1e-9)
            assertEquals(vector.name, vector.azimuthDegrees, result.azimuthDegrees, MICRO_DEGREE)
        }
    }

    @Test
    fun everyReferenceVectorIsInsideTheAcceptanceTolerance() {
        for (vector in VECTORS) {
            val result = Geodesic.inverse(vector.from, vector.to)
            val distanceError = abs(result.distanceMeters - vector.distanceMeters) / vector.distanceMeters
            val azimuthError = abs(result.azimuthDegrees - vector.azimuthDegrees)

            assertTrue("${vector.name} distance error $distanceError", distanceError <= ACCEPTANCE_DISTANCE)
            assertTrue("${vector.name} azimuth error $azimuthError", azimuthError <= ACCEPTANCE_AZIMUTH_DEGREES)
        }
    }

    @Test
    fun theAntimeridianCrossingIsTheShortWayRound() {
        val eastbound =
            Geodesic.inverse(
                GeoPoint(latitude = 48.0, longitude = 179.9),
                GeoPoint(latitude = 48.1, longitude = -179.9),
            )
        val westbound =
            Geodesic.inverse(
                GeoPoint(latitude = 48.0, longitude = -179.9),
                GeoPoint(latitude = 48.1, longitude = 179.9),
            )

        assertEquals(18_600.051_923, eastbound.distanceMeters, MILLIMETRE)
        assertEquals(53.213_115, eastbound.azimuthDegrees, MICRO_DEGREE)
        // The reverse line is not the reverse azimuth of the outbound one, but
        // the distance is the same to the millimetre.
        assertEquals(eastbound.distanceMeters, westbound.distanceMeters, MILLIMETRE)
    }

    @Test
    fun aNearPolePairGivesTheReferenceBearing() {
        val result =
            Geodesic.inverse(
                GeoPoint(latitude = 89.9, longitude = 0.0),
                GeoPoint(latitude = 89.95, longitude = 180.0),
            )

        assertEquals(16_754.096_805, result.distanceMeters, MILLIMETRE)
        assertEquals(0.0, result.azimuthDegrees, MICRO_DEGREE)
    }

    @Test
    fun aWesternBearingIsNormalisedIntoTheFullCircle() {
        val result =
            Geodesic.inverse(
                GeoPoint(latitude = -33.8688, longitude = 151.2093),
                GeoPoint(latitude = -37.8136, longitude = 144.9631),
            )

        assertEquals(713_857.665_117, result.distanceMeters, MILLIMETRE)
        assertTrue(result.azimuthDegrees >= 0.0)
        assertTrue(result.azimuthDegrees < FULL_CIRCLE_DEGREES)
        assertEquals(230.403_303, result.azimuthDegrees, MICRO_DEGREE)
    }

    @Test
    fun distanceIsSymmetricButTheInitialBearingIsNotSimplyReversed() {
        val lviv = GeoPoint(latitude = 49.8419, longitude = 24.0316)
        val kyiv = GeoPoint(latitude = 50.4501, longitude = 30.5234)
        val outbound = Geodesic.inverse(lviv, kyiv)
        val inbound = Geodesic.inverse(kyiv, lviv)

        assertEquals(outbound.distanceMeters, inbound.distanceMeters, MILLIMETRE)
        // On an ellipsoid the reverse initial bearing is not 180 degrees from
        // the outbound one; GeographicLib gives -95.7931199489719, normalised
        // here.
        assertEquals(264.206_880, inbound.azimuthDegrees, MICRO_DEGREE)
    }

    @Test
    fun coincidentPointsMeasureNothing() {
        val point = GeoPoint(latitude = 49.8419, longitude = 24.0316)

        assertEquals(GeodesicResult(distanceMeters = 0.0, azimuthDegrees = 0.0), Geodesic.inverse(point, point))
    }

    @Test
    fun anAntipodalPairFallsBackToASphereInsteadOfHanging() {
        val result =
            Geodesic.inverse(
                GeoPoint(latitude = 0.0, longitude = 0.0),
                GeoPoint(latitude = 0.0, longitude = 180.0),
            )

        // Half the meridian is 20 003.9 km; the mean-radius sphere gives
        // 20 015.1 km, which is the documented fallback bound.
        assertEquals(HALF_MERIDIAN_METERS, result.distanceMeters, HALF_MERIDIAN_METERS * ACCEPTANCE_DISTANCE)
        assertTrue(result.azimuthDegrees.isFinite())
    }

    private data class Vector(
        val name: String,
        val from: GeoPoint,
        val to: GeoPoint,
        val distanceMeters: Double,
        val azimuthDegrees: Double,
    )

    private companion object {
        /** The first fix of `test-data/location/mock-walk.gpx`. */
        val MOCK_FIX = GeoPoint(latitude = 49.8419, longitude = 24.0316)

        /** Reference values from GeographicLib's WGS84 inverse solution. */
        val VECTORS =
            listOf(
                Vector(
                    name = "mock fix",
                    from = MOCK_FIX,
                    to = GeoPoint(latitude = 49.8500, longitude = 24.0400),
                    distanceMeters = 1_084.755_494,
                    azimuthDegrees = 33.842_680,
                ),
                Vector(
                    name = "equator",
                    from = GeoPoint(latitude = 0.0, longitude = 0.0),
                    to = GeoPoint(latitude = 0.0, longitude = 1.0),
                    distanceMeters = 111_319.490_793,
                    azimuthDegrees = 90.0,
                ),
                Vector(
                    name = "east-west at 50 north",
                    from = GeoPoint(latitude = 50.0, longitude = 30.0),
                    to = GeoPoint(latitude = 50.0, longitude = 31.0),
                    distanceMeters = 71_695.219_606,
                    azimuthDegrees = 89.616_974,
                ),
                Vector(
                    name = "antimeridian",
                    from = GeoPoint(latitude = 48.0, longitude = 179.9),
                    to = GeoPoint(latitude = 48.1, longitude = -179.9),
                    distanceMeters = 18_600.051_923,
                    azimuthDegrees = 53.213_115,
                ),
                Vector(
                    name = "near pole",
                    from = GeoPoint(latitude = 89.9, longitude = 0.0),
                    to = GeoPoint(latitude = 89.95, longitude = 180.0),
                    distanceMeters = 16_754.096_805,
                    azimuthDegrees = 0.0,
                ),
                Vector(
                    name = "southern diagonal",
                    from = GeoPoint(latitude = -33.8688, longitude = 151.2093),
                    to = GeoPoint(latitude = -37.8136, longitude = 144.9631),
                    distanceMeters = 713_857.665_117,
                    azimuthDegrees = 230.403_303,
                ),
                Vector(
                    name = "lviv to kyiv",
                    from = GeoPoint(latitude = 49.8419, longitude = 24.0316),
                    to = GeoPoint(latitude = 50.4501, longitude = 30.5234),
                    distanceMeters = 468_772.792_501,
                    azimuthDegrees = 79.220_990,
                ),
                Vector(
                    name = "south-west diagonal",
                    from = GeoPoint(latitude = -20.0, longitude = -70.0),
                    to = GeoPoint(latitude = -21.5, longitude = -68.0),
                    distanceMeters = 266_379.584_643,
                    azimuthDegrees = 128.916_924,
                ),
            )

        const val MILLIMETRE = 0.001
        const val MICRO_DEGREE = 1e-6
        const val FULL_CIRCLE_DEGREES = 360.0
        const val ACCEPTANCE_DISTANCE = 0.005
        const val ACCEPTANCE_AZIMUTH_DEGREES = 1.0
        const val HALF_MERIDIAN_METERS = 20_003_931.458
    }
}
