package nl.r1a.grassmap.map

import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Long-press recognition for point selection (T-0112). The emulator can inject
 * a hold, but the decision itself is driven here through the same tracker the
 * pointer loop uses.
 */
class LongPressTest {
    private val tracker =
        LongPressTracker(
            maxTravelDp = MapGestures.LONG_PRESS_MAX_TRAVEL_DP,
            minDurationMillis = MapGestures.LONG_PRESS_MIN_MILLIS,
        )

    @Test
    fun aStillPressHeldPastTheThresholdSelectsItsPoint() {
        tracker.onDown(pointerId = 0, position = ScreenOffset(40f, 60f), atMillis = 1_000L)

        assertTrue(tracker.confirm(atMillis = 1_000L + MapGestures.LONG_PRESS_MIN_MILLIS))
        assertEquals(ScreenOffset(40f, 60f), tracker.point())
    }

    @Test
    fun aPressThatMovesBeforeTheThresholdIsNotASelection() {
        tracker.onDown(pointerId = 0, position = ScreenOffset(40f, 60f), atMillis = 1_000L)
        tracker.onMove(
            pointerId = 0,
            position = ScreenOffset(40f, 60f + MapGestures.LONG_PRESS_MAX_TRAVEL_DP + 1f),
        )

        assertFalse(tracker.confirm(atMillis = 1_000L + MapGestures.LONG_PRESS_MIN_MILLIS))
    }

    @Test
    fun movementInsideTheSlopStillSelects() {
        tracker.onDown(pointerId = 0, position = ScreenOffset(40f, 60f), atMillis = 1_000L)
        tracker.onMove(
            pointerId = 0,
            position = ScreenOffset(40f, 60f + MapGestures.LONG_PRESS_MAX_TRAVEL_DP),
        )

        assertTrue(tracker.confirm(atMillis = 1_000L + MapGestures.LONG_PRESS_MIN_MILLIS))
    }

    @Test
    fun aShortPressIsNotASelection() {
        tracker.onDown(pointerId = 0, position = ScreenOffset(40f, 60f), atMillis = 1_000L)
        tracker.onUp(pointerId = 0)

        assertFalse(tracker.confirm(atMillis = 1_000L + MapGestures.LONG_PRESS_MIN_MILLIS - 1L))
    }

    @Test
    fun aSecondFingerIsNotASelection() {
        tracker.onDown(pointerId = 0, position = ScreenOffset(40f, 60f), atMillis = 1_000L)
        tracker.onDown(pointerId = 1, position = ScreenOffset(140f, 60f), atMillis = 1_010L)

        assertFalse(tracker.confirm(atMillis = 1_000L + MapGestures.LONG_PRESS_MIN_MILLIS))
    }

    @Test
    fun movingAfterTheSelectionDoesNotWithdrawIt() {
        tracker.onDown(pointerId = 0, position = ScreenOffset(40f, 60f), atMillis = 1_000L)
        assertTrue(tracker.confirm(atMillis = 1_000L + MapGestures.LONG_PRESS_MIN_MILLIS))

        tracker.onMove(pointerId = 0, position = ScreenOffset(400f, 600f))

        assertFalse(tracker.confirm(atMillis = 1_000L + 2 * MapGestures.LONG_PRESS_MIN_MILLIS))
        assertTrue(tracker.isConfirmed())
        assertEquals(ScreenOffset(40f, 60f), tracker.point())
    }
}
