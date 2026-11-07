package nl.r1a.grassmap.map

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two-finger tap recognition and what it asks the map to do (T-0105). The
 * emulator cannot inject a second pointer, so the decision is driven here
 * through the same seam the gesture binding reports to.
 */
class TwoFingerTapTest {
    private val tracker =
        TwoFingerTapTracker(
            maxTravelDp = MapGestures.TWO_FINGER_TAP_MAX_TRAVEL_DP,
            maxDurationMillis = MapGestures.TWO_FINGER_TAP_MAX_MILLIS,
        )

    @Test
    fun twoStillFingersAreATap() {
        tracker.onDown(pointerId = 0, position = ScreenOffset(10f, 10f), atMillis = 1_000L)
        tracker.onDown(pointerId = 1, position = ScreenOffset(90f, 10f), atMillis = 1_010L)

        tracker.onUp(pointerId = 0)
        tracker.onUp(pointerId = 1)

        assertTrue(tracker.isTap(atMillis = 1_100L))
        assertEquals(ScreenOffset(10f, 10f) to ScreenOffset(90f, 10f), tracker.tapPoints())
    }

    @Test
    fun aFingerThatTravelsPastTheSlopIsNotATap() {
        tracker.onDown(pointerId = 0, position = ScreenOffset(10f, 10f), atMillis = 1_000L)
        tracker.onDown(pointerId = 1, position = ScreenOffset(90f, 10f), atMillis = 1_010L)

        tracker.onMove(pointerId = 1, position = ScreenOffset(90f, 10f + MapGestures.TWO_FINGER_TAP_MAX_TRAVEL_DP + 1f))
        tracker.onUp(pointerId = 0)
        tracker.onUp(pointerId = 1)

        assertFalse(tracker.isTap(atMillis = 1_100L))
    }

    @Test
    fun aHoldPastTheLimitIsNotATap() {
        tracker.onDown(pointerId = 0, position = ScreenOffset(10f, 10f), atMillis = 1_000L)
        tracker.onDown(pointerId = 1, position = ScreenOffset(90f, 10f), atMillis = 1_010L)

        tracker.onUp(pointerId = 0)
        tracker.onUp(pointerId = 1)

        assertFalse(tracker.isTap(atMillis = 1_000L + MapGestures.TWO_FINGER_TAP_MAX_MILLIS + 1L))
    }

    @Test
    fun aThirdFingerIsNotATap() {
        tracker.onDown(pointerId = 0, position = ScreenOffset(10f, 10f), atMillis = 1_000L)
        tracker.onDown(pointerId = 1, position = ScreenOffset(90f, 10f), atMillis = 1_010L)
        tracker.onDown(pointerId = 2, position = ScreenOffset(50f, 90f), atMillis = 1_020L)

        tracker.onUp(pointerId = 0)
        tracker.onUp(pointerId = 1)
        tracker.onUp(pointerId = 2)

        assertFalse(tracker.isTap(atMillis = 1_100L))
    }

    @Test
    fun aSingleFingerIsNotATap() {
        tracker.onDown(pointerId = 0, position = ScreenOffset(10f, 10f), atMillis = 1_000L)
        tracker.onUp(pointerId = 0)

        assertFalse(tracker.isTap(atMillis = 1_050L))
    }

    @Test
    fun twoTapsOneAfterAnotherAreNotAFingerPair() {
        tracker.onDown(pointerId = 0, position = ScreenOffset(10f, 10f), atMillis = 1_000L)
        tracker.onUp(pointerId = 0)
        tracker.onDown(pointerId = 1, position = ScreenOffset(90f, 10f), atMillis = 1_100L)
        tracker.onUp(pointerId = 1)

        assertFalse(tracker.isTap(atMillis = 1_200L))
    }

    @Test
    fun withTheSettingOffTheTapZoomsOutExactlyOneLevel() {
        val action =
            twoFingerTapAction(
                distanceByTap = false,
                cameraZoom = 12.5,
                from = ScreenOffset(10f, 10f),
                to = ScreenOffset(90f, 10f),
                projector = MapScreenProjector { error("the zoom path must not project") },
            )

        assertEquals(TwoFingerTapAction.ZoomOut(11.5), action)
        assertEquals(1.0, MapGestures.TWO_FINGER_TAP_ZOOM_LEVELS, 0.0)
    }

    @Test
    fun withTheSettingOnTheShownDistanceIsTheHaversineDistanceOfTheProjectedPoints() {
        val action =
            twoFingerTapAction(
                distanceByTap = true,
                cameraZoom = 12.5,
                from = ScreenOffset(0f, 0f),
                to = ScreenOffset(0f, 1_000f),
                projector = degreePerThousandDpProjector(),
            ) as TwoFingerTapAction.Distance

        val shown = MapDistance.readout(action.meters)
        assertEquals(DistanceUnit.Metres, shown.unit)
        assertEquals(111.2, shown.value, 0.0)
        assertTrue(Math.abs(shown.value - DEGREE_THOUSANDTH_METERS) / DEGREE_THOUSANDTH_METERS < 0.01)
    }

    @Test
    fun anUnprojectablePointMeasuresNothing() {
        val action =
            twoFingerTapAction(
                distanceByTap = true,
                cameraZoom = 12.5,
                from = ScreenOffset(0f, 0f),
                to = ScreenOffset(0f, 1_000f),
                projector = MapScreenProjector { null },
            )

        assertEquals(TwoFingerTapAction.Ignored, action)
    }

    /** A linear projection where 1000 dp north of the origin is 0.001 degrees. */
    private fun degreePerThousandDpProjector(): MapScreenProjector =
        MapScreenProjector { point ->
            GeoPoint(latitude = point.y / DEGREES_PER_THOUSAND_DP_DIVISOR * DEGREE_STEP, longitude = 0.0)
        }

    private companion object {
        const val DEGREE_STEP = 0.001
        const val DEGREES_PER_THOUSAND_DP_DIVISOR = 1_000f

        /** 0.001 degrees of latitude on the IUGG mean radius. */
        const val DEGREE_THOUSANDTH_METERS = 111.194_926_639
    }
}
