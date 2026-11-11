package nl.r1a.grassmap.plugins.info.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The speed derivation on its own, without Android, a clock or coroutines.
 *
 * The acceptance line for T-0316 is "on a replay at known speeds the widgets
 * are within 0.5 km/h". These tests hold a tighter bound: the fixture's
 * metres-per-degree constant differs from the tracker's Earth radius by 0.1 %,
 * which is 0.005 km/h at 5 km/h.
 */
class SpeedTrackerTest {
    @Test
    fun aReplayAtAKnownSpeedReadsThatSpeedEverywhere() {
        val tracker = SpeedTracker()
        tracker.offer(SpeedReplay.initialFix())
        SpeedReplay.fixes(speedKmh = 5.0, seconds = 60).forEach(tracker::offer)

        val reading = tracker.reading
        assertKmh(5.0, reading.currentMetersPerSecond)
        assertKmh(5.0, reading.averageMetersPerSecond)
        assertKmh(5.0, reading.maxMetersPerSecond)
    }

    @Test
    fun aStationaryReplayReportsZero() {
        val tracker = SpeedTracker()
        tracker.offer(SpeedReplay.initialFix())
        SpeedReplay.fixes(speedKmh = 0.0, seconds = 4).forEach(tracker::offer)

        val reading = tracker.reading
        assertKmh(0.0, reading.currentMetersPerSecond)
        assertKmh(0.0, reading.averageMetersPerSecond)
        assertKmh(0.0, reading.maxMetersPerSecond)
    }

    @Test
    fun aSingleFixFabricatesNoSpeedAndNoAverage() {
        val reading = SpeedTracker().offer(SpeedReplay.initialFix())

        assertNull(reading.currentMetersPerSecond)
        assertNull(reading.averageMetersPerSecond)
        assertNull(reading.maxMetersPerSecond)
    }

    @Test
    fun aSpeedSpikeMovesTheMaxButBarelyTheWindowedAverage() {
        val tracker = SpeedTracker()
        tracker.offer(SpeedReplay.initialFix())
        val cruise = SpeedReplay.fixes(speedKmh = 5.0, seconds = 60)
        cruise.forEach(tracker::offer)
        val spike = SpeedReplay.fixes(speedKmh = SpeedReplay.SPIKE_KMH, seconds = 1, from = cruise.last()).single()
        tracker.offer(spike)
        SpeedReplay.fixes(speedKmh = 5.0, seconds = 1, from = spike).forEach(tracker::offer)

        val reading = tracker.reading
        assertKmh(5.0, reading.currentMetersPerSecond)
        // One 20 km/h interval among the 60 in the window: (58*5 + 20 + 5) / 60.
        assertKmh(5.25, reading.averageMetersPerSecond)
        assertKmh(SpeedReplay.SPIKE_KMH, reading.maxMetersPerSecond)
    }

    @Test
    fun theAverageForgetsIntervalsOlderThanTheWindow() {
        val tracker = SpeedTracker()
        tracker.offer(SpeedReplay.initialFix())
        val slow = SpeedReplay.fixes(speedKmh = 5.0, seconds = 60)
        slow.forEach(tracker::offer)
        SpeedReplay.fixes(speedKmh = 15.0, seconds = 60, from = slow.last()).forEach(tracker::offer)

        assertKmh(15.0, tracker.reading.averageMetersPerSecond)
        assertKmh(15.0, tracker.reading.currentMetersPerSecond)
    }

    @Test
    fun resettingTheMaxClearsItUntilTheNextInterval() {
        val tracker = SpeedTracker()
        tracker.offer(SpeedReplay.initialFix())
        SpeedReplay.fixes(speedKmh = 20.0, seconds = 3).forEach(tracker::offer)
        assertKmh(20.0, tracker.reading.maxMetersPerSecond)

        tracker.resetMax()
        assertNull(tracker.reading.maxMetersPerSecond)
        assertKmh(20.0, tracker.reading.currentMetersPerSecond)

        val fast = SpeedReplay.fixes(speedKmh = 20.0, seconds = 3).last()
        SpeedReplay.fixes(speedKmh = 5.0, seconds = 1, from = fast).forEach(tracker::offer)
        assertKmh(5.0, tracker.reading.maxMetersPerSecond)
    }

    @Test
    fun aFixThatIsNotNewerChangesNothing() {
        val tracker = SpeedTracker()
        tracker.offer(SpeedReplay.initialFix())
        val first = SpeedReplay.fixes(speedKmh = 5.0, seconds = 1).single()
        tracker.offer(first)
        val reading = tracker.reading

        tracker.offer(first.copy(position = SpeedReplay.start))
        assertEquals(reading, tracker.reading)
    }

    @Test
    fun aNullFixClearsTheCurrentSpeedAndKeepsTheMax() {
        val tracker = SpeedTracker()
        tracker.offer(SpeedReplay.initialFix())
        val first = SpeedReplay.fixes(speedKmh = 5.0, seconds = 1).single()
        tracker.offer(first)

        val reading = tracker.offer(null)
        assertNull(reading.currentMetersPerSecond)
        assertKmh(5.0, reading.averageMetersPerSecond)
        assertKmh(5.0, reading.maxMetersPerSecond)

        // The next fix starts a new interval; the outage is not a measured gap.
        val resumed = SpeedReplay.fixes(speedKmh = 5.0, seconds = 1, from = first).single()
        assertNull(tracker.offer(resumed).currentMetersPerSecond)
    }

    private fun assertKmh(
        expectedKmh: Double,
        actualMetersPerSecond: Double?,
    ) {
        assertEquals(expectedKmh, (actualMetersPerSecond ?: Double.NaN) * SpeedReplay.MPS_TO_KMH, SpeedReplay.TOLERANCE_KMH)
    }
}
