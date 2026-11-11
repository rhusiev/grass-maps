package nl.r1a.grassmap.plugins.info.widgets

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.widget.AltitudeSource
import nl.r1a.grassmap.plugin.api.widget.AltitudeWidgetValue
import nl.r1a.grassmap.plugin.api.widget.CoordinatesWidgetValue
import nl.r1a.grassmap.plugin.api.widget.GpsInfoWidgetValue
import nl.r1a.grassmap.plugin.api.widget.SpeedWidgetValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InfoWidgetsPluginTest {
    @Test
    fun activationDeclaresSixWidgetsOverSixStreamsAndNoProviders() =
        runTest {
            val context = context()
            val plugin = InfoWidgetsPlugin(UnconfinedTestDispatcher(testScheduler))

            assertEquals(Outcome.Success(Unit), plugin.activate(context))

            assertEquals(
                setOf(
                    InfoWidgetsContract.altitudeStream,
                    InfoWidgetsContract.coordinatesStream,
                    InfoWidgetsContract.gpsStream,
                    InfoWidgetsContract.speedCurrentStream,
                    InfoWidgetsContract.speedAverageStream,
                    InfoWidgetsContract.speedMaxStream,
                ),
                context.streams.map { it.id }.toSet(),
            )
            assertEquals(
                setOf(
                    InfoWidgetsContract.ALTITUDE_STREAM_ID,
                    InfoWidgetsContract.COORDINATES_STREAM_ID,
                    InfoWidgetsContract.GPS_STREAM_ID,
                    InfoWidgetsContract.SPEED_CURRENT_STREAM_ID,
                    InfoWidgetsContract.SPEED_AVERAGE_STREAM_ID,
                    InfoWidgetsContract.SPEED_MAX_STREAM_ID,
                ),
                context.widgets.map { it.id.value }.toSet(),
            )
            assertTrue(
                context.widgets.all { widget ->
                    context.streams.any { stream ->
                        stream.id == widget.streamId && stream.valueType == widget.valueType
                    }
                },
            )
            assertTrue(context.providers.isEmpty())
            assertTrue(context.commands.isEmpty())
            plugin.deactivate()
        }

    @Test
    fun theAltitudeStreamFollowsTheFixAtTheStandInPeak() =
        runTest {
            val fixes = MutableStateFlow<LocationFix?>(null)
            val context = context(fixes)
            val plugin = InfoWidgetsPlugin(UnconfinedTestDispatcher(testScheduler))
            plugin.activate(context)
            val flow = context.altitudeFlow()

            fixes.value = fixAt(ElevationOracle.peak)
            val value = withTimeout(5_000) { flow.first { it.source == AltitudeSource.Dem } }

            assertEquals(
                ElevationOracle.PEAK_METERS,
                value.altitudeMeters ?: Double.NaN,
                ElevationOracle.TOLERANCE_METERS,
            )
            plugin.deactivate()
        }

    @Test
    fun theCoordinatesAndGpsStreamsShowTheMockedFixValues() =
        runTest {
            val fixes = MutableStateFlow<LocationFix?>(null)
            val context = context(fixes)
            val plugin = InfoWidgetsPlugin(UnconfinedTestDispatcher(testScheduler))
            plugin.activate(context)
            val coordinates = context.coordinatesFlow()
            val gps = context.gpsFlow()

            fixes.value =
                fixAt(
                    ElevationOracle.peak,
                    accuracyMeters = 3.0,
                    speedMetersPerSecond = 1.2,
                    bearingDegrees = 180.0,
                    timeMillis = 1_792_000_321_000L,
                )
            val position = withTimeout(5_000) { coordinates.first { it.fix != null } }
            val info = withTimeout(5_000) { gps.first { it.fixTimeMillis != null } }

            assertEquals(ElevationOracle.peak, position.fix)
            assertEquals(3.0, info.accuracyMeters ?: Double.NaN, 0.0)
            assertEquals(1.2, info.speedMetersPerSecond ?: Double.NaN, 0.0)
            assertEquals(180.0, info.bearingDegrees ?: Double.NaN, 0.0)
            assertEquals(1_792_000_321_000L, info.fixTimeMillis)
            plugin.deactivate()
        }

    @Test
    fun theThreeSpeedStreamsCarryTheirOwnValue() =
        runTest {
            val fixes = MutableStateFlow<LocationFix?>(null)
            val clock = FakeClock()
            val context = context(fixes, clock)
            val plugin = InfoWidgetsPlugin(UnconfinedTestDispatcher(testScheduler))
            plugin.activate(context)
            val current = speedValues(context, SpeedMetric.Current)
            val average = speedValues(context, SpeedMetric.Average)
            val max = speedValues(context, SpeedMetric.Max)

            val cruise = listOf(SpeedReplay.initialFix()) + SpeedReplay.fixes(speedKmh = 5.0, seconds = 60)
            cruise.forEach { fix ->
                clock.now = fix.timeMillis
                fixes.value = fix
            }
            val spike = SpeedReplay.fixes(speedKmh = SpeedReplay.SPIKE_KMH, seconds = 1, from = cruise.last()).single()
            clock.now = spike.timeMillis
            fixes.value = spike

            assertKmh(SpeedReplay.SPIKE_KMH, current.last().speedMetersPerSecond)
            assertKmh(5.25, average.last().speedMetersPerSecond)
            assertKmh(SpeedReplay.SPIKE_KMH, max.last().speedMetersPerSecond)
            plugin.deactivate()
        }

    @Test
    fun theDeclaredValueTypesMatchTheSerializers() {
        assertEquals(
            InfoWidgetsContract.ALTITUDE_VALUE_TYPE,
            AltitudeWidgetValue.serializer().descriptor.serialName,
        )
        assertEquals(
            InfoWidgetsContract.COORDINATES_VALUE_TYPE,
            CoordinatesWidgetValue.serializer().descriptor.serialName,
        )
        assertEquals(
            InfoWidgetsContract.GPS_VALUE_TYPE,
            GpsInfoWidgetValue.serializer().descriptor.serialName,
        )
        assertEquals(
            InfoWidgetsContract.SPEED_VALUE_TYPE,
            SpeedWidgetValue.serializer().descriptor.serialName,
        )
    }

    private fun context(
        fixes: MutableStateFlow<LocationFix?> = MutableStateFlow(null),
        clock: FakeClock = FakeClock(),
    ): RecordingContext =
        RecordingContext(
            FakeServices(
                fixes = fixes,
                elevation = ElevationOracle.access,
                clock = clock,
            ),
        )

    @Suppress("UNCHECKED_CAST")
    private fun RecordingContext.altitudeFlow(): Flow<AltitudeWidgetValue> =
        streams.single { it.id == InfoWidgetsContract.altitudeStream }.flow as Flow<AltitudeWidgetValue>

    @Suppress("UNCHECKED_CAST")
    private fun RecordingContext.coordinatesFlow(): Flow<CoordinatesWidgetValue> =
        streams.single { it.id == InfoWidgetsContract.coordinatesStream }.flow as Flow<CoordinatesWidgetValue>

    @Suppress("UNCHECKED_CAST")
    private fun RecordingContext.gpsFlow(): Flow<GpsInfoWidgetValue> =
        streams.single { it.id == InfoWidgetsContract.gpsStream }.flow as Flow<GpsInfoWidgetValue>

    private fun TestScope.speedValues(
        context: RecordingContext,
        metric: SpeedMetric,
    ): MutableList<SpeedWidgetValue> {
        val streamId =
            when (metric) {
                SpeedMetric.Current -> InfoWidgetsContract.speedCurrentStream
                SpeedMetric.Average -> InfoWidgetsContract.speedAverageStream
                SpeedMetric.Max -> InfoWidgetsContract.speedMaxStream
            }
        val values = mutableListOf<SpeedWidgetValue>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            @Suppress("UNCHECKED_CAST")
            val flow = context.streams.single { it.id == streamId }.flow as Flow<SpeedWidgetValue>
            flow.toList(values)
        }
        return values
    }

    private fun assertKmh(
        expectedKmh: Double,
        actualMetersPerSecond: Double?,
    ) {
        assertEquals(expectedKmh, (actualMetersPerSecond ?: Double.NaN) * SpeedReplay.MPS_TO_KMH, SpeedReplay.TOLERANCE_KMH)
    }
}
