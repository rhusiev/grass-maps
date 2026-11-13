package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's annotated route segments as stretches of the profile's own
 * distance axis (T-0406).
 *
 * The spans are the point of the test: the strips may only be as long as the
 * profile they sit on, and their ends are the engine's segment ends read with
 * the profile's own haversine step.
 */
class RouteStretchesTest {
    @Test
    fun theStretchesCoverTheProfileAtTheEngineSegmentEnds() {
        val profile = profile(GEOMETRY.size)

        val stretches = RouteStretches().of(profile, GEOMETRY, SEGMENTS)

        assertEquals(2, stretches.size)
        assertEquals(0.0, stretches[0].startMeters, EPSILON)
        assertEquals(profile.samples[2].distanceMeters, stretches[0].endMeters, EPSILON)
        assertEquals(profile.samples[2].distanceMeters, stretches[1].startMeters, EPSILON)
        assertEquals(profile.distanceMeters, stretches[1].endMeters, EPSILON)
    }

    @Test
    fun theStretchedLengthsAddUpToTheProfilesDistance() {
        val profile = profile(GEOMETRY.size)

        val stretches = RouteStretches().of(profile, GEOMETRY, SEGMENTS)

        assertEquals(profile.distanceMeters, stretches.sumOf { it.distanceMeters }, EPSILON)
    }

    @Test
    fun aStretchKeepsTheEnginesOwnValues() {
        val profile = profile(GEOMETRY.size)

        val stretches = RouteStretches().of(profile, GEOMETRY, SEGMENTS)

        assertEquals("asphalt", stretches[0].surface)
        assertEquals("residential", stretches[0].wayType)
        assertEquals("unknown", stretches[1].surface)
        assertEquals("track", stretches[1].wayType)
    }

    @Test
    fun aStretchWithoutAttributesKeepsThemNull() {
        val profile = profile(GEOMETRY.size)
        val bare = listOf(RouteSegment(0, GEOMETRY.lastIndex, profile.distanceMeters, 0.0))

        val stretches = RouteStretches().of(profile, GEOMETRY, bare)

        assertEquals(1, stretches.size)
        assertNull(stretches[0].surface)
        assertNull(stretches[0].wayType)
    }

    @Test
    fun aValueOfAnotherTypeIsNoValue() {
        val profile = profile(GEOMETRY.size)
        val segment =
            RouteSegment(
                startIndex = 0,
                endIndex = GEOMETRY.lastIndex,
                distanceMeters = profile.distanceMeters,
                durationSeconds = 0.0,
                attributes = mapOf(RouteAttributes.SURFACE to AttributeValue.NumberValue(2.0)),
            )

        val stretches = RouteStretches().of(profile, GEOMETRY, listOf(segment))

        assertNull(stretches[0].surface)
    }

    @Test
    fun aSegmentOutsideTheProfileIsClampedToIt() {
        val profile = profile(GEOMETRY.size)
        val segment = RouteSegment(-4, 400, profile.distanceMeters, 0.0)

        val stretches = RouteStretches().of(profile, GEOMETRY, listOf(segment))

        assertEquals(1, stretches.size)
        assertEquals(0.0, stretches[0].startMeters, EPSILON)
        assertEquals(profile.distanceMeters, stretches[0].endMeters, EPSILON)
    }

    @Test
    fun aLineWithoutSegmentsOrProfileHasNoStretches() {
        val profile = profile(GEOMETRY.size)

        assertTrue(RouteStretches().of(profile, GEOMETRY, emptyList()).isEmpty())
        assertTrue(RouteStretches().of(ElevationProfile(), GEOMETRY, SEGMENTS).isEmpty())
        assertTrue(RouteStretches().of(profile, listOf(GEOMETRY.first()), SEGMENTS).isEmpty())
    }

    /** A strided profile keeps the line's own distances, not the chords of its samples. */
    @Test
    fun aProfileSkippingPointsStillMeasuresTheWholeLine() {
        val full = profile(GEOMETRY.size)
        val strided =
            ElevationProfiler().lineProfile(
                positions = GEOMETRY,
                elevations = GEOMETRY.indices.map { index -> if (index % 2 == 0) 300.0 else null },
                durationMillis = DURATION_MILLIS,
                departureEpochMillis = DEPARTURE_MILLIS,
            )

        assertEquals(3, strided.samples.size)
        assertEquals(full.distanceMeters, strided.distanceMeters, EPSILON)
        assertEquals(full.samples[2].distanceMeters, strided.samples[1].distanceMeters, EPSILON)
    }

    private fun profile(size: Int): ElevationProfile =
        ElevationProfiler().lineProfile(
            positions = GEOMETRY.take(size),
            elevations = List(size) { 300.0 },
            durationMillis = DURATION_MILLIS,
            departureEpochMillis = DEPARTURE_MILLIS,
        )

    private companion object {
        const val EPSILON = 1e-6
        const val LATITUDE = 49.84
        const val LONGITUDE = 24.03
        const val LATITUDE_STEP = 0.001
        const val DURATION_MILLIS = 600_000L
        const val DEPARTURE_MILLIS = 1_700_000_000_000L

        val GEOMETRY = (0..4).map { index -> GeoPoint(LATITUDE + index * LATITUDE_STEP, LONGITUDE) }

        val SEGMENTS =
            listOf(
                RouteSegment(
                    startIndex = 0,
                    endIndex = 2,
                    distanceMeters = 222.4,
                    durationSeconds = 60.0,
                    attributes =
                        mapOf(
                            RouteAttributes.SURFACE to AttributeValue.TextValue("asphalt"),
                            RouteAttributes.HIGHWAY to AttributeValue.TextValue("residential"),
                        ),
                ),
                RouteSegment(
                    startIndex = 2,
                    endIndex = 4,
                    distanceMeters = 222.4,
                    durationSeconds = 60.0,
                    attributes =
                        mapOf(
                            RouteAttributes.SURFACE to AttributeValue.TextValue("unknown"),
                            RouteAttributes.HIGHWAY to AttributeValue.TextValue("track"),
                        ),
                ),
            )
    }
}
