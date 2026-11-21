package nl.r1a.grassmaps.tracks

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.TrackPoint
import nl.r1a.grassmaps.plugin.api.model.TrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * The cleaned line: the gate, the simplification and the distance along it.
 *
 * Every track here is generated in the test with a deterministic wander, so a
 * failure names a filter rather than a recording. The wander is uniform in
 * [-amplitude, amplitude] on both axes from a fixed seed, which is what a fix
 * with a few metres of noise looks like at the scale of one step.
 */
class TrackGeometryTest {
    @Test
    fun aStraightNoisyWalkIsMeasuredAtItsLength() {
        val segment = straightWalk(points = 200, stepMeters = 2.7, wanderMeters = 4.0)

        val geometry = TrackGeometry.of(segment, TrackFilterPolicy())

        val truth = 199 * 2.7
        val raw = rawSum(segment)
        assertTrue("the fixture must carry a wander, raw $raw against truth $truth", raw > truth * 1.4)
        assertTrue(
            "a straight walk is its own length, was ${geometry.distanceMeters} against $truth",
            abs(geometry.distanceMeters - truth) <= truth * 0.03,
        )
        assertTrue("the wander must not be counted", geometry.distanceMeters < truth * 1.03)
    }

    @Test
    fun aCornerIsKept() {
        val segment = cornerWalk(legMeters = 200.0, stepMeters = 2.7, wanderMeters = 3.0)

        val geometry = TrackGeometry.of(segment, TrackFilterPolicy())

        // The chord between the two ends of a right angle is 71 % of the path.
        assertTrue(
            "the corner must survive, was ${geometry.distanceMeters}",
            abs(geometry.distanceMeters - 400.0) <= 12.0,
        )
    }

    @Test
    fun aFixTheTrackCannotHaveReachedIsDropped() {
        val segment = straightWalk(points = 200, stepMeters = 2.7, wanderMeters = 3.0, spikeAt = 100)

        val geometry = TrackGeometry.of(segment, TrackFilterPolicy())

        val truth = 199 * 2.7
        assertEquals("the spike is not part of the walk", 199, geometry.keptIndices.size)
        assertTrue(
            "the walk is its own length, was ${geometry.distanceMeters}",
            abs(geometry.distanceMeters - truth) <= truth * 0.03,
        )
    }

    @Test
    fun aStationaryFixSetHasAlmostNoDistance() {
        val segment = stationaryWalk(points = 60, wanderMeters = 3.0)

        val geometry = TrackGeometry.of(segment, TrackFilterPolicy())

        assertTrue("standing still is not a walk, was ${geometry.distanceMeters}", geometry.distanceMeters < 20.0)
    }

    @Test
    fun theDistanceAlongTheLineIsOneNonDecreasingReach() {
        val segment = straightWalk(points = 80, stepMeters = 3.0, wanderMeters = 4.0)

        val geometry = TrackGeometry.of(segment, TrackFilterPolicy())

        assertEquals(segment.points.size, geometry.alongMeters.size)
        assertEquals(0.0, geometry.alongMeters.first(), 0.0)
        assertEquals(geometry.distanceMeters, geometry.alongMeters.last(), 0.0)
        for (index in 1 until geometry.alongMeters.size) {
            assertTrue(
                "along distance went backwards at $index",
                geometry.alongMeters[index] >= geometry.alongMeters[index - 1],
            )
        }
    }

    @Test
    fun aCleanLineKeepsOnlyItsEnds() {
        val segment = straightWalk(points = 60, stepMeters = 5.0, wanderMeters = 0.0)

        val geometry = TrackGeometry.of(segment, TrackFilterPolicy())

        assertEquals(60, geometry.keptIndices.size)
        assertEquals(0, geometry.keptIndices.first())
        assertEquals(59, geometry.keptIndices.last())
        assertEquals(1, geometry.legs.size)
        assertEquals(59 * 5.0, geometry.distanceMeters, 1e-6)
        assertEquals(0.0, geometry.positionNoiseMeters, 1e-6)
        assertEquals(0.0, geometry.toleranceMeters, 1e-6)
    }

    @Test
    fun aHumanStepsFarEnoughToBeMeasuredFromTheTimestamps() {
        val segment = straightWalk(points = 21, stepMeters = 2.7, wanderMeters = 0.0)

        val geometry = TrackGeometry.of(segment, TrackFilterPolicy())

        assertEquals(2_000L, geometry.medianIntervalMillis)
    }

    @Test
    fun aSegmentWithoutTimestampsStillHasALine() {
        val segment =
            TrackSegment(
                (0 until 40).map { index ->
                    TrackPoint(point(latitude = BASE_LATITUDE + index * 0.0001, longitude = BASE_LONGITUDE))
                },
            )

        val geometry = TrackGeometry.of(segment, TrackFilterPolicy())

        assertTrue("a line without time is still a line", geometry.distanceMeters > 400.0)
        assertNull(geometry.medianIntervalMillis)
    }

    private fun straightWalk(
        points: Int,
        stepMeters: Double,
        wanderMeters: Double,
        spikeAt: Int = -1,
    ): TrackSegment {
        val wander = Wander(SEED)
        val trackPoints =
            (0 until points).map { index ->
                var north = index * stepMeters
                var east = 0.0
                if (index > 0) {
                    north += wander.meters(wanderMeters)
                    east += wander.meters(wanderMeters)
                }
                if (index == spikeAt) {
                    east += SPIKE_METERS
                }
                timedPoint(north, east, index * STEP_MILLIS)
            }
        return TrackSegment(trackPoints)
    }

    private fun cornerWalk(
        legMeters: Double,
        stepMeters: Double,
        wanderMeters: Double,
    ): TrackSegment {
        val wander = Wander(SEED)
        val perLeg = (legMeters / stepMeters).toInt()
        val trackPoints = mutableListOf<TrackPoint>()
        var north = 0.0
        var east = 0.0
        for (index in 0 until perLeg * 2 + 1) {
            if (index in 1..perLeg) {
                north += stepMeters
            } else if (index > perLeg) {
                east += stepMeters
            }
            val jitterNorth = if (index == 0 || index == perLeg * 2) 0.0 else wander.meters(wanderMeters)
            val jitterEast = if (index == 0 || index == perLeg * 2) 0.0 else wander.meters(wanderMeters)
            trackPoints += timedPoint(north + jitterNorth, east + jitterEast, index * STEP_MILLIS)
        }
        return TrackSegment(trackPoints)
    }

    private fun stationaryWalk(
        points: Int,
        wanderMeters: Double,
    ): TrackSegment {
        val wander = Wander(SEED)
        return TrackSegment(
            (0 until points).map { index ->
                timedPoint(wander.meters(wanderMeters), wander.meters(wanderMeters), index * STEP_MILLIS)
            },
        )
    }

    private fun rawSum(segment: TrackSegment): Double =
        (1 until segment.points.size).sumOf { index ->
            distanceMeters(segment.points[index - 1].position, segment.points[index].position)
        }

    private fun timedPoint(
        northMeters: Double,
        eastMeters: Double,
        timeMillis: Long,
        elevationMeters: Double? = null,
    ): TrackPoint =
        TrackPoint(
            position =
                point(
                    latitude = BASE_LATITUDE + northMeters / METERS_PER_DEGREE,
                    longitude = BASE_LONGITUDE + eastMeters / (METERS_PER_DEGREE * COSINE_OF_BASE_LATITUDE),
                ),
            elevationMeters = elevationMeters,
            timeMillis = timeMillis,
        )

    private fun point(
        latitude: Double,
        longitude: Double,
    ): GeoPoint = GeoPoint(latitude = latitude, longitude = longitude)

    /** Uniform in `[-amplitude, amplitude]` from a fixed seed, so a run is reproducible. */
    private class Wander(
        seed: Int,
    ) {
        private var state: Int = seed

        fun meters(amplitude: Double): Double {
            state = state * LCG_MULTIPLIER + LCG_INCREMENT
            val unit = ((state ushr 16) and LCG_MASK) / LCG_HALF_RANGE - 1.0
            return unit * amplitude
        }
    }

    private companion object {
        const val SEED = 20_260_111
        const val LCG_MULTIPLIER = 1_103_515_245
        const val LCG_INCREMENT = 12_345
        const val LCG_MASK = 0x7FFF
        const val LCG_HALF_RANGE = 16_384.0
        const val BASE_LATITUDE = 49.84
        const val BASE_LONGITUDE = 24.03
        val METERS_PER_DEGREE = EARTH_RADIUS_METERS * PI / 180.0
        val COSINE_OF_BASE_LATITUDE = Math.cos(Math.toRadians(BASE_LATITUDE))
        const val STEP_MILLIS = 2_000L
        const val SPIKE_METERS = 250.0
    }
}
