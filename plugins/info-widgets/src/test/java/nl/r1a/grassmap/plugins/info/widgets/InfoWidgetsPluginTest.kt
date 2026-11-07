package nl.r1a.grassmap.plugins.info.widgets

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.widget.AltitudeSource
import nl.r1a.grassmap.plugin.api.widget.AltitudeWidgetValue
import nl.r1a.grassmap.plugin.api.widget.CoordinatesWidgetValue
import nl.r1a.grassmap.plugin.api.widget.GpsInfoWidgetValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InfoWidgetsPluginTest {
    @Test
    fun activationDeclaresThreeWidgetsOverThreeStreamsAndNoProviders() =
        runTest {
            val context = context()
            val plugin = InfoWidgetsPlugin(UnconfinedTestDispatcher(testScheduler))

            assertEquals(Outcome.Success(Unit), plugin.activate(context))

            assertEquals(
                setOf(
                    InfoWidgetsContract.altitudeStream,
                    InfoWidgetsContract.coordinatesStream,
                    InfoWidgetsContract.gpsStream,
                ),
                context.streams.map { it.id }.toSet(),
            )
            assertEquals(
                setOf(
                    InfoWidgetsContract.ALTITUDE_STREAM_ID,
                    InfoWidgetsContract.COORDINATES_STREAM_ID,
                    InfoWidgetsContract.GPS_STREAM_ID,
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
    }

    private fun context(fixes: MutableStateFlow<LocationFix?> = MutableStateFlow(null)): RecordingContext =
        RecordingContext(
            FakeServices(
                fixes = fixes,
                elevation = ElevationOracle.access,
                clock = FakeClock(),
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
}
