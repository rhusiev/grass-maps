package nl.r1a.grassmap.plugins.info.widgets

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.widget.CoordinatesWidgetValue
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinatesWidgetProviderTest {
    @Test
    fun theFixAndTheMapCentreAreBothPresent() =
        runTest {
            val value =
                firstWithCentre(
                    fixes = MutableStateFlow<LocationFix?>(fixAt(FIX_POINT)),
                    camera = MutableStateFlow(Camera(center = CENTRE_POINT, zoom = 14.0)),
                )

            assertEquals(CoordinatesWidgetValue(FIX_POINT, CENTRE_POINT), value)
        }

    @Test
    fun aFixIsShownEvenWhenNoCameraIsBoundYet() =
        runTest {
            val provider = provider(MutableStateFlow(fixAt(FIX_POINT)), emptyFlow(), FakeClock())

            val value = withTimeout(5_000) { provider.values.first() }

            assertEquals(CoordinatesWidgetValue(FIX_POINT, null), value)
        }

    @Test
    fun aMovedCameraProducesANewCentreOnceTheCadenceAllowsIt() =
        runTest {
            val clock = FakeClock()
            val fixes = MutableStateFlow<LocationFix?>(fixAt(FIX_POINT))
            val camera = MutableSharedFlow<Camera>(extraBufferCapacity = 8)
            val provider = provider(fixes, camera, clock)
            val collected = mutableListOf<CoordinatesWidgetValue>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                provider.values.toList(collected)
            }

            camera.emit(Camera(center = CENTRE_POINT, zoom = 14.0))
            camera.emit(Camera(center = OTHER_POINT, zoom = 14.0))
            clock.now = 1_000
            camera.emit(Camera(center = THIRD_POINT, zoom = 14.0))

            assertEquals(
                listOf(CENTRE_POINT, THIRD_POINT),
                collected.mapNotNull { it.mapCenter },
            )
            assertEquals(FIX_POINT, collected.last().fix)
        }

    private suspend fun TestScope.firstWithCentre(
        fixes: MutableStateFlow<LocationFix?>,
        camera: Flow<Camera>,
    ): CoordinatesWidgetValue {
        val provider = provider(fixes, camera, FakeClock())
        return withTimeout(5_000) { provider.values.first { value -> value.mapCenter != null } }
    }

    private fun provider(
        fixes: Flow<LocationFix?>,
        camera: Flow<Camera>,
        clock: FakeClock,
    ): CoordinatesWidgetProvider =
        CoordinatesWidgetProvider(
            fixes = fixes,
            camera = camera,
            clock = clock,
        )

    private companion object {
        val FIX_POINT = GeoPoint(latitude = 49.9490, longitude = 23.9570)
        val CENTRE_POINT = GeoPoint(latitude = 49.8400, longitude = 24.0300)
        val OTHER_POINT = GeoPoint(latitude = 49.8500, longitude = 24.0400)
        val THIRD_POINT = GeoPoint(latitude = 49.8600, longitude = 24.0500)
    }
}
