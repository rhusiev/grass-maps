package nl.r1a.grassmap.plugins.point.info

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The point-info plugin (T-0112): registration, and the distance and azimuth
 * it publishes for a selected point.
 */
class PointInfoPluginTest {
    private val mockFix = GeoPoint(latitude = 49.8419, longitude = 24.0316)
    private val selected = GeoPoint(latitude = 49.8500, longitude = 24.0400)
    private val mapCentre = GeoPoint(latitude = 49.8600, longitude = 24.0500)

    @Test
    fun activationDeclaresOneSelectionStreamAndOneWidget() =
        runTest {
            val context = context()
            val plugin = PointInfoPlugin(SelectedPointStore(), UnconfinedTestDispatcher(testScheduler))

            assertEquals(Outcome.Success(Unit), plugin.activate(context))

            val stream = context.streams.single()
            assertEquals(PointInfoContract.selectionStream, stream.id)
            assertEquals(PointInfoContract.SELECTION_VALUE_TYPE, stream.valueType)
            val widget = context.widgets.single()
            assertEquals(PointInfoContract.SELECTION_WIDGET_ID, widget.id.value)
            assertEquals(PointInfoContract.SELECTION_TITLE, widget.title)
            assertEquals(stream.id, widget.streamId)
            assertEquals(stream.valueType, widget.valueType)
            assertTrue(context.commands.isEmpty())
            assertTrue(context.providers.isEmpty())
            plugin.deactivate()
        }

    @Test
    fun theDeclaredValueTypeMatchesTheSerializer() {
        assertEquals(
            PointInfoContract.SELECTION_VALUE_TYPE,
            SelectedPointValue.serializer().descriptor.serialName,
        )
    }

    @Test
    fun theStreamMeasuresFromTheMockFixToTheSelectedPoint() =
        runTest {
            val fixes = MutableStateFlow<LocationFix?>(null)
            val camera = MutableStateFlow<Camera?>(cameraAt(mapCentre))
            val store = SelectedPointStore()
            val context = context(fixes = fixes, camera = camera)
            val plugin = PointInfoPlugin(store, UnconfinedTestDispatcher(testScheduler))
            plugin.activate(context)
            val values = context.selectionFlow()

            fixes.value = fixAt(mockFix)
            store.select(selected)
            val value = withTimeout(TIMEOUT_MILLIS) { values.first { it.distanceMeters != null } }

            assertEquals(selected, value.point)
            assertEquals(1_084.755_494, value.distanceMeters ?: Double.NaN, 0.001)
            assertEquals(33.842_680, value.azimuthDegrees ?: Double.NaN, 1e-6)
            plugin.deactivate()
        }

    @Test
    fun withoutAFixTheStreamMeasuresFromTheMapCentre() =
        runTest {
            val fixes = MutableStateFlow<LocationFix?>(null)
            val camera = MutableStateFlow<Camera?>(cameraAt(mapCentre))
            val store = SelectedPointStore(selected)
            val context = context(fixes = fixes, camera = camera)
            val plugin = PointInfoPlugin(store, UnconfinedTestDispatcher(testScheduler))
            plugin.activate(context)
            val values = context.selectionFlow()

            val value = withTimeout(TIMEOUT_MILLIS) { values.first { it.distanceMeters != null } }

            val expected = Geodesic.inverse(mapCentre, selected)
            assertEquals(expected.distanceMeters, value.distanceMeters ?: Double.NaN, 0.001)
            assertEquals(expected.azimuthDegrees, value.azimuthDegrees ?: Double.NaN, 1e-6)
            plugin.deactivate()
        }

    @Test
    fun clearingTheSelectionEmptiesTheStream() =
        runTest {
            val store = SelectedPointStore(selected)
            val context = context(fixes = MutableStateFlow(fixAt(mockFix)))
            val plugin = PointInfoPlugin(store, UnconfinedTestDispatcher(testScheduler))
            plugin.activate(context)
            val values = context.selectionFlow()
            withTimeout(TIMEOUT_MILLIS) { values.first { it.point != null } }

            store.clear()
            val cleared = withTimeout(TIMEOUT_MILLIS) { values.first { it.point == null } }

            assertNull(cleared.distanceMeters)
            assertNull(cleared.azimuthDegrees)
            plugin.deactivate()
        }

    @Test
    fun aSelectionWithNoOriginYetStillReportsThePoint() =
        runTest {
            val store = SelectedPointStore(selected)
            val context = context(fixes = MutableStateFlow(null), camera = MutableStateFlow(null))
            val plugin = PointInfoPlugin(store, UnconfinedTestDispatcher(testScheduler))
            plugin.activate(context)
            val values = context.selectionFlow()

            val value = withTimeout(TIMEOUT_MILLIS) { values.first { it.point != null } }

            assertNull(value.distanceMeters)
            assertNull(value.azimuthDegrees)
            plugin.deactivate()
        }

    private fun context(
        fixes: MutableStateFlow<LocationFix?> = MutableStateFlow(null),
        camera: MutableStateFlow<Camera?> = MutableStateFlow(cameraAt(mapCentre)),
    ): RecordingContext =
        RecordingContext(
            FakeServices(
                fixes = fixes,
                camera = camera.mapNotNull { it },
            ),
        )

    @Suppress("UNCHECKED_CAST")
    private fun RecordingContext.selectionFlow(): Flow<SelectedPointValue> =
        streams.single { it.id == PointInfoContract.selectionStream }.flow as Flow<SelectedPointValue>

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
