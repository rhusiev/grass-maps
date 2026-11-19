package nl.r1a.grassmap.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** The age label's boundaries: seconds, minutes, hours, then a capped age. */
class PuckAgeTextTest {
    @Test
    fun aFixUnderTenSecondsIsJustNow() {
        assertEquals(PuckAgeLabel.JustNow, puckAgeLabel(0L))
        assertEquals(PuckAgeLabel.JustNow, puckAgeLabel(9_999L))
    }

    @Test
    fun secondsRunToTheMinuteBoundary() {
        assertEquals(PuckAgeLabel.Seconds(10L), puckAgeLabel(10_000L))
        assertEquals(PuckAgeLabel.Seconds(59L), puckAgeLabel(59_999L))
        assertEquals(PuckAgeLabel.Minutes(1L), puckAgeLabel(60_000L))
    }

    @Test
    fun minutesRunToTheHourBoundary() {
        assertEquals(PuckAgeLabel.Minutes(59L), puckAgeLabel(3_599_999L))
        assertEquals(PuckAgeLabel.Hours(1L), puckAgeLabel(3_600_000L))
    }

    @Test
    fun hoursRunToTheDayBoundary() {
        assertEquals(PuckAgeLabel.Hours(23L), puckAgeLabel(86_399_999L))
        assertEquals(PuckAgeLabel.LongAgo, puckAgeLabel(86_400_000L))
    }

    @Test
    fun theElevenDayOldFixIsNotCountedInMinutes() {
        assertEquals(PuckAgeLabel.LongAgo, puckAgeLabel(16_474L * 60_000L))
        assertEquals(PuckAgeLabel.LongAgo, puckAgeLabel(Long.MAX_VALUE))
    }

    @Test
    fun aNegativeAgeReadsJustNow() {
        assertEquals(PuckAgeLabel.JustNow, puckAgeLabel(-1_000L))
    }
}
