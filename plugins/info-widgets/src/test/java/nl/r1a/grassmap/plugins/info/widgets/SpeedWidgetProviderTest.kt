package nl.r1a.grassmap.plugins.info.widgets

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.widget.SpeedWidgetValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three widget streams over one tracker.
 *
 * The replay is the deterministic known-speed fixture; the acceptance bound is
 * 0.5 km/h and these tests hold 0.05 km/h, as `SpeedTrackerTest` explains.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeedWidgetProviderTest {
    @Test
    fun theKnownSpeedReplayReachesTheThreeWidgets() =
        runTest(UnconfinedTestDispatcher()) {
            val clock = FakeClock()
            val fixes = MutableSharedFlow<LocationFix?>(extraBufferCapacity = 16)
            val provider = SpeedWidgetProvider(fixes = fixes, clock = clock, scope = backgroundScope)
            val current = collect(provider, SpeedMetric.Current)
            val average = collect(provider, SpeedMetric.Average)
            val max = collect(provider, SpeedMetric.Max)

            send(fixes, clock, SpeedReplay.initialFix())
            SpeedReplay.fixes(speedKmh = 5.0, seconds = 60).forEach { fix -> send(fixes, clock, fix) }

            assertKmh(5.0, current.last().speedMetersPerSecond)
            assertKmh(5.0, average.last().speedMetersPerSecond)
            assertKmh(5.0, max.last().speedMetersPerSecond)
        }

    @Test
    fun aNullFixClearsOnlyTheCurrentWidget() =
        runTest(UnconfinedTestDispatcher()) {
            val clock = FakeClock()
            val fixes = MutableSharedFlow<LocationFix?>(extraBufferCapacity = 16)
            val provider = SpeedWidgetProvider(fixes = fixes, clock = clock, scope = backgroundScope)
            val current = collect(provider, SpeedMetric.Current)
            val average = collect(provider, SpeedMetric.Average)
            val max = collect(provider, SpeedMetric.Max)

            send(fixes, clock, SpeedReplay.initialFix())
            SpeedReplay.fixes(speedKmh = 5.0, seconds = 2).forEach { fix -> send(fixes, clock, fix) }
            send(fixes, clock, null)

            assertNull(current.last().speedMetersPerSecond)
            assertKmh(5.0, average.last().speedMetersPerSecond)
            assertKmh(5.0, max.last().speedMetersPerSecond)
        }

    @Test
    fun aFixInsideTheWidgetIntervalIsDropped() =
        runTest(UnconfinedTestDispatcher()) {
            val clock = FakeClock()
            val fixes = MutableSharedFlow<LocationFix?>(extraBufferCapacity = 16)
            val provider = SpeedWidgetProvider(fixes = fixes, clock = clock, scope = backgroundScope)
            val current = collect(provider, SpeedMetric.Current)

            send(fixes, clock, SpeedReplay.initialFix())
            SpeedReplay.fixes(speedKmh = 5.0, seconds = 3).forEach { fix ->
                send(fixes, clock, fix)
                // One millisecond later, inside the 200 ms widget interval. If it
                // passed, the zero-distance interval would read 0 m/s.
                send(fixes, clock, fix.copy(timeMillis = fix.timeMillis + 1))
            }

            assertTrue(current.isNotEmpty())
            val speeds = current.mapNotNull { it.speedMetersPerSecond }
            assertTrue(speeds.isNotEmpty())
            speeds.forEach { assertKmh(5.0, it) }
        }

    private fun TestScope.collect(
        provider: SpeedWidgetProvider,
        metric: SpeedMetric,
    ): MutableList<SpeedWidgetValue> {
        val values = mutableListOf<SpeedWidgetValue>()
        backgroundScope.launch { provider.values(metric).toList(values) }
        return values
    }

    private suspend fun send(
        fixes: MutableSharedFlow<LocationFix?>,
        clock: FakeClock,
        fix: LocationFix?,
    ) {
        // A null fix carries no time; place it one tick after the previous one so
        // the widget interval lets it through.
        clock.now = fix?.timeMillis ?: (clock.now + SpeedReplay.TICK_MILLIS)
        fixes.emit(fix)
    }

    private fun assertKmh(
        expectedKmh: Double,
        actualMetersPerSecond: Double?,
    ) {
        assertEquals(expectedKmh, (actualMetersPerSecond ?: Double.NaN) * SpeedReplay.MPS_TO_KMH, SpeedReplay.TOLERANCE_KMH)
    }
}
