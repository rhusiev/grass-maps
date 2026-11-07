package nl.r1a.grassmap.plugins.info.widgets

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.widget.AltitudeSource
import nl.r1a.grassmap.plugin.api.widget.AltitudeWidgetValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AltitudeWidgetProviderTest {
    @Test
    fun atTheStandInPeakTheDemHeightIsWithinTheOracleTolerance() =
        runTest {
            val provider = provider(ElevationOracle.access)

            val value = provider.readingFor(fixAt(ElevationOracle.peak, altitudeMeters = 999.0))

            assertEquals(AltitudeSource.Dem, value.source)
            assertTrue(
                "DEM height ${value.altitudeMeters} differs from ${ElevationOracle.PEAK_METERS}",
                abs((value.altitudeMeters ?: Double.NaN) - ElevationOracle.PEAK_METERS) <=
                    ElevationOracle.TOLERANCE_METERS,
            )
        }

    @Test
    fun withoutDemDataTheFixAltitudeIsUsed() =
        runTest {
            val provider = provider(ElevationAccess { Outcome.Success(null) })

            val value = provider.readingFor(fixAt(ELSEWHERE, altitudeMeters = 612.0))

            assertEquals(AltitudeWidgetValue(612.0, AltitudeSource.Gps), value)
        }

    @Test
    fun aFailingDemFallsBackToTheFixAltitude() =
        runTest {
            val provider = provider(FailingElevation)

            val value = provider.readingFor(fixAt(ELSEWHERE, altitudeMeters = 455.5))

            assertEquals(AltitudeWidgetValue(455.5, AltitudeSource.Gps), value)
        }

    @Test
    fun aFixWithoutAnyAltitudeShowsNoSource() =
        runTest {
            val provider = provider(ElevationAccess { Outcome.Success(null) })

            assertEquals(
                AltitudeWidgetValue(null, AltitudeSource.None),
                provider.readingFor(fixAt(ELSEWHERE)),
            )
            assertEquals(
                AltitudeWidgetValue(null, AltitudeSource.None),
                provider.readingFor(null),
            )
        }

    @Test
    fun aNewFixReachesTheStreamWithinTheCadence() =
        runTest {
            val clock = FakeClock()
            val fixes = MutableSharedFlow<LocationFix?>(extraBufferCapacity = 4)
            val provider = provider(ElevationOracle.access, fixes, clock)
            val collected = mutableListOf<AltitudeWidgetValue>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                provider.values.toList(collected)
            }

            fixes.emit(fixAt(ElevationOracle.peak))
            clock.now = 1_000
            fixes.emit(fixAt(ELSEWHERE, altitudeMeters = 300.0))

            assertEquals(2, collected.size)
            assertEquals(AltitudeSource.Dem, collected[0].source)
            assertEquals(AltitudeSource.Gps, collected[1].source)
        }

    private fun provider(
        elevation: ElevationAccess,
        fixes: MutableSharedFlow<LocationFix?> = MutableSharedFlow(extraBufferCapacity = 4),
        clock: FakeClock = FakeClock(),
    ): AltitudeWidgetProvider =
        AltitudeWidgetProvider(
            fixes = fixes,
            elevation = elevation,
            clock = clock,
        )

    private companion object {
        val ELSEWHERE = GeoPoint(latitude = 49.9410, longitude = 23.9410)
    }
}
