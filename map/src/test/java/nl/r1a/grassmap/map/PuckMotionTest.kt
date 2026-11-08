package nl.r1a.grassmap.map

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PuckMotionTest {
    @Test
    fun interpolationUsesTheStartMiddleAndEndOfTheFixInterval() {
        val motion = PuckMotion()
        val start = fix(latitude = LATITUDE, timeMillis = 0L)
        val end = fix(latitude = LATITUDE + 1.4 / METRES_PER_DEGREE_LATITUDE, timeMillis = 1_000L)
        motion.onFix(start, nowElapsedMillis = 0L, nowWallMillis = 0L)

        assertNull(motion.onFix(end, nowElapsedMillis = 0L, nowWallMillis = 1_000L))

        val atStart = motion.frame(nowElapsedMillis = 0L, nowWallMillis = 1_000L, zoom = ZOOM)
        val atMiddle = motion.frame(nowElapsedMillis = 500L, nowWallMillis = 1_500L, zoom = ZOOM)
        val atEnd = motion.frame(nowElapsedMillis = 1_000L, nowWallMillis = 2_000L, zoom = ZOOM)

        assertEquals(start.position.latitude, requireNotNull(atStart).fix.position.latitude, EPSILON)
        assertEquals(start.position.latitude + 0.7 / METRES_PER_DEGREE_LATITUDE, requireNotNull(atMiddle).fix.position.latitude, EPSILON)
        assertEquals(end.position.latitude, requireNotNull(atEnd).fix.position.latitude, EPSILON)
    }

    @Test
    fun aGappedFixAnimatesFromTheShownPositionInsteadOfJumping() {
        val motion = PuckMotion()
        val start = fix(latitude = LATITUDE, timeMillis = 0L)
        val end = fix(latitude = LATITUDE + 200.0 / METRES_PER_DEGREE_LATITUDE, timeMillis = 10_000L)
        motion.onFix(start, nowElapsedMillis = 0L, nowWallMillis = 0L)

        motion.onFix(end, nowElapsedMillis = 100L, nowWallMillis = 10_000L)

        val first = motion.frame(nowElapsedMillis = 100L, nowWallMillis = 10_000L, zoom = ZOOM)
        val middle = motion.frame(nowElapsedMillis = 600L, nowWallMillis = 10_500L, zoom = ZOOM)
        val last = motion.frame(nowElapsedMillis = 1_100L, nowWallMillis = 11_000L, zoom = ZOOM)
        assertEquals(start.position.latitude, requireNotNull(first).fix.position.latitude, EPSILON)
        assertEquals(
            start.position.latitude + 100.0 / METRES_PER_DEGREE_LATITUDE,
            requireNotNull(middle).fix.position.latitude,
            EPSILON,
        )
        assertEquals(end.position.latitude, requireNotNull(last).fix.position.latitude, EPSILON)
    }

    @Test
    fun belowBothDeadbandThresholdsNoFrameUpdates() {
        val motion = PuckMotion()
        val start = fix(latitude = LATITUDE, timeMillis = 0L)
        val end = fix(latitude = LATITUDE + metresForDp(0.9) / METRES_PER_DEGREE_LATITUDE, timeMillis = 1_000L, speed = 0.4)
        motion.onFix(start, nowElapsedMillis = 0L, nowWallMillis = 0L)
        motion.onFix(end, nowElapsedMillis = 0L, nowWallMillis = 0L)

        val updates = (0..60).map { step -> motion.frame(frameMillis(step), 1_000L + frameMillis(step), ZOOM) }

        assertTrue(updates.all { it == null })
    }

    @Test
    fun speedJustAboveTheThresholdUpdatesEvenUnderOnePixel() {
        val motion = PuckMotion()
        val start = fix(latitude = LATITUDE, timeMillis = 0L)
        val end = fix(latitude = LATITUDE + metresForDp(0.9) / METRES_PER_DEGREE_LATITUDE, timeMillis = 1_000L, speed = 0.51)
        motion.onFix(start, nowElapsedMillis = 0L, nowWallMillis = 0L)
        motion.onFix(end, nowElapsedMillis = 0L, nowWallMillis = 0L)

        val updates = (0..60).count { step -> motion.frame(frameMillis(step), 1_000L + frameMillis(step), ZOOM) != null }

        assertTrue(updates >= 60)
    }

    @Test
    fun screenDeltaJustAboveOnePixelUpdatesAtZeroSpeed() {
        val motion = PuckMotion()
        val start = fix(latitude = LATITUDE, timeMillis = 0L)
        val end = fix(latitude = LATITUDE + metresForDp(1.2) / METRES_PER_DEGREE_LATITUDE, timeMillis = 1_000L, speed = 0.0)
        motion.onFix(start, nowElapsedMillis = 0L, nowWallMillis = 0L)
        motion.onFix(end, nowElapsedMillis = 0L, nowWallMillis = 0L)

        val updates = (0..60).count { step -> motion.frame(frameMillis(step), 1_000L + frameMillis(step), ZOOM) != null }

        assertTrue(updates > 0)
        assertFalse(motion.isAnimating)
    }

    @Test
    fun aStationaryFixProducesNoFrameUpdates() {
        val motion = PuckMotion()
        val start = fix(latitude = LATITUDE, timeMillis = 0L, speed = 0.0)
        val end = fix(latitude = LATITUDE, timeMillis = 1_000L, speed = 0.0)
        motion.onFix(start, nowElapsedMillis = 0L, nowWallMillis = 0L)
        motion.onFix(end, nowElapsedMillis = 0L, nowWallMillis = 0L)

        val updates = (0..60).count { step -> motion.frame(frameMillis(step), 1_000L + frameMillis(step), ZOOM) != null }

        assertEquals(0, updates)
    }

    @Test
    fun oneHertzWalkingFixRunsOnEveryDisplayFrame() {
        val motion = PuckMotion()
        val start = fix(latitude = LATITUDE, timeMillis = 0L, speed = 1.4)
        val end =
            fix(
                latitude = LATITUDE + 1.4 / METRES_PER_DEGREE_LATITUDE,
                timeMillis = 1_000L,
                speed = 1.4,
            )
        motion.onFix(start, nowElapsedMillis = 0L, nowWallMillis = 0L)
        motion.onFix(end, nowElapsedMillis = 0L, nowWallMillis = 1_000L)

        val updates =
            (0..FRAMES_PER_SECOND).count { step ->
                motion.frame(
                    nowElapsedMillis = frameMillis(step),
                    nowWallMillis = 1_000L + frameMillis(step),
                    zoom = ZOOM,
                ) != null
            }

        assertTrue("expected about 60 updates, got $updates", updates in 59..61)
    }

    @Test
    fun aNullFixClearsThePuckAndStopsFrames() {
        val motion = PuckMotion()
        motion.onFix(fix(latitude = LATITUDE, timeMillis = 0L), nowElapsedMillis = 0L, nowWallMillis = 0L)
        motion.onFix(fix(latitude = LATITUDE + 0.001, timeMillis = 1_000L), nowElapsedMillis = 0L, nowWallMillis = 0L)

        val cleared = motion.onFix(null, nowElapsedMillis = 100L, nowWallMillis = 1_000L)

        assertNull(cleared)
        assertNull(motion.frame(nowElapsedMillis = 200L, nowWallMillis = 1_200L, zoom = ZOOM))
        assertFalse(motion.isAnimating)
    }

    @Test
    fun theDisplayMarksStaleAgeAndPoorAccuracy() {
        val fresh = puckDisplay(fix(latitude = LATITUDE, timeMillis = 1_000L), nowWallMillis = 2_000L)
        val poor = puckDisplay(fix(latitude = LATITUDE, timeMillis = 1_000L, accuracy = 50.0), nowWallMillis = 2_000L)
        val atBoundary = puckDisplay(fix(latitude = LATITUDE, timeMillis = 1_000L), nowWallMillis = 11_000L)
        val stale = puckDisplay(fix(latitude = LATITUDE, timeMillis = 1_000L), nowWallMillis = 11_001L)

        assertEquals(1_000L, fresh.ageMillis)
        assertFalse(fresh.isStale)
        assertFalse(fresh.showAccuracy)
        assertTrue(poor.showAccuracy)
        assertTrue(atBoundary.isStale)
        assertTrue(stale.isStale)
    }

    @Test
    fun theAccuracyRadiusGrowsWithPoorAccuracyAndShrinksWithZoom() {
        val wide = PuckGeometry.accuracyRadiusDp(100.0, LATITUDE, ZOOM)
        val narrow = PuckGeometry.accuracyRadiusDp(100.0, LATITUDE, ZOOM + 2.0)

        assertTrue(narrow > wide)
        assertTrue(wide > 0f)
    }

    private fun frameMillis(step: Int): Long = step * 1_000L / FRAMES_PER_SECOND

    private fun metresForDp(dp: Double): Double = dp * PuckGeometry.metersPerPixel(LATITUDE, ZOOM)

    private fun fix(
        latitude: Double,
        timeMillis: Long,
        speed: Double? = null,
        accuracy: Double = 5.0,
    ): LocationFix =
        LocationFix(
            position = GeoPoint(latitude = latitude, longitude = LONGITUDE),
            accuracyMeters = accuracy,
            speedMetersPerSecond = speed,
            timeMillis = timeMillis,
        )

    private companion object {
        const val LATITUDE = 49.84
        const val LONGITUDE = 24.03
        const val ZOOM = 16.0
        const val EPSILON = 1e-9
        const val FRAMES_PER_SECOND = 60
        const val METRES_PER_DEGREE_LATITUDE = 111_194.93
    }
}
