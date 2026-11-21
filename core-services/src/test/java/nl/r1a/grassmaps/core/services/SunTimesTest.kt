package nl.r1a.grassmaps.core.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * The sun boundary (T-0111). The reference value comes from NOAA's published
 * 2026 sunset table for Lviv at 49.84 N, 24.02 E: 21 December is 16:25 local
 * (Europe/Kyiv is UTC+2 in winter), so 14:25 UTC.
 */
class SunTimesTest {
    private val lvivLatitude = 49.84
    private val lvivLongitude = 24.02
    private val date = LocalDate.of(2026, 12, 21)
    private val publishedSunset = Instant.parse("2026-12-21T14:25:00Z")

    @Test
    fun noaaSunsetIsWithinOneMinuteOfThePublishedValue() {
        val times = sunTimesFor(date, lvivLatitude, lvivLongitude)

        val driftSeconds = abs(Duration.between(times.sunset, publishedSunset).toMillis() / 1000.0)
        assertTrue("sunset=${times.sunset}, published=$publishedSunset, drift=${driftSeconds}s", driftSeconds <= 60.0)
    }

    @Test
    fun theNightFlagFlipsOnTheSunsetSecond() {
        val times = sunTimesFor(date, lvivLatitude, lvivLongitude)

        assertFalse(times.isNight(times.sunset.minusSeconds(1)))
        assertTrue(times.isNight(times.sunset))
        assertTrue(times.isNight(times.sunset.plusSeconds(1)))
        assertFalse(times.isNight(times.sunrise))
        assertTrue(times.isNight(times.sunrise.minusSeconds(1)))
    }

    @Test
    fun anInjectedClockAndLocationProduceTheSameBoundary() {
        val clock = Clock.fixed(Instant.parse("2026-12-21T12:00:00Z"), ZoneOffset.UTC)
        val times = sunTimesForLocation(clock, lvivLatitude, lvivLongitude)

        assertEquals(sunTimesFor(date, lvivLatitude, lvivLongitude), times)
        assertFalse(times.isNight(clock.instant()))
        assertTrue(times.isNight(Clock.fixed(times.sunset.plusSeconds(1), ZoneOffset.UTC).instant()))
    }

    @Test
    fun aMockedClockFlipsWithinOneMinuteOfSunset() {
        val times = sunTimesFor(date, lvivLatitude, lvivLongitude)
        val before = Clock.fixed(times.sunset.minusSeconds(59), ZoneOffset.UTC)
        val after = Clock.fixed(times.sunset.plusSeconds(1), ZoneOffset.UTC)

        assertFalse(times.isNight(before.instant()))
        assertTrue(times.isNight(after.instant()))
        assertEquals(60, Duration.between(before.instant(), after.instant()).seconds)
    }
}
