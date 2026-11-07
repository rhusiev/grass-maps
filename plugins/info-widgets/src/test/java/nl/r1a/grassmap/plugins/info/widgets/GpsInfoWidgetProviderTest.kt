package nl.r1a.grassmap.plugins.info.widgets

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.widget.GpsInfoWidgetValue
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsInfoWidgetProviderTest {
    @Test
    fun everyGpsFieldComesFromTheFix() {
        val provider = provider(MutableSharedFlow())
        val fix =
            fixAt(
                GeoPoint(latitude = 49.9490, longitude = 23.9570),
                accuracyMeters = 3.5,
                speedMetersPerSecond = 1.4,
                bearingDegrees = 271.0,
                timeMillis = 1_792_000_123_000L,
            )

        assertEquals(
            GpsInfoWidgetValue(
                accuracyMeters = 3.5,
                speedMetersPerSecond = 1.4,
                bearingDegrees = 271.0,
                fixTimeMillis = 1_792_000_123_000L,
            ),
            provider.readingOf(fix),
        )
    }

    @Test
    fun withoutAFixEveryFieldIsNull() {
        val provider = provider(MutableSharedFlow())

        assertEquals(
            GpsInfoWidgetValue(
                accuracyMeters = null,
                speedMetersPerSecond = null,
                bearingDegrees = null,
                fixTimeMillis = null,
            ),
            provider.readingOf(null),
        )
    }

    @Test
    fun aFixReachesTheStream() =
        runTest {
            val clock = FakeClock()
            val fixes = MutableSharedFlow<LocationFix?>(extraBufferCapacity = 4)
            val provider = provider(fixes, clock)
            val collected = mutableListOf<GpsInfoWidgetValue>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                provider.values.toList(collected)
            }

            fixes.emit(fixAt(GeoPoint(49.9490, 23.9570), accuracyMeters = 2.0))

            assertEquals(2.0, collected.single().accuracyMeters ?: 0.0, 0.0)
        }

    private fun provider(
        fixes: MutableSharedFlow<LocationFix?>,
        clock: FakeClock = FakeClock(),
    ): GpsInfoWidgetProvider =
        GpsInfoWidgetProvider(
            fixes = fixes,
            clock = clock,
        )
}
