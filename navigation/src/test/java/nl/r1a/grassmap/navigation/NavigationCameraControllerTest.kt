package nl.r1a.grassmap.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.MapEvent
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.model.TrackingMode
import nl.r1a.grassmap.plugin.api.provider.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation camera (T-0313) against a fake host and a fake clock.
 *
 * The fixes are built by moving a point east by the distance a speed covers in
 * one second, so the controller measures the speed it is given through the same
 * consecutive-fix arithmetic production uses. No test sleeps.
 */
class NavigationCameraControllerTest {
    @Test
    fun zoomFollowsTheWalkBikeAndCarBands() =
        runTest {
            val drive = Drive()
            val host = RecordingMapHost()
            val controller = controller(host)
            controller.onState(following(drive.first()))
            controller.onState(following(drive.at(speedKmh = 5.0)))
            controller.onState(following(drive.at(speedKmh = 15.0)))
            controller.onState(rerouting(drive.at(speedKmh = 30.0)))

            assertEquals(listOf(17.0, 16.0, 15.0), host.zooms())
        }

    @Test
    fun theFirstFixAloneDoesNotChangeTheZoom() =
        runTest {
            val drive = Drive()
            val host = RecordingMapHost()
            val controller = controller(host)

            controller.onState(following(drive.first()))

            assertTrue(host.zooms().isEmpty())
        }

    @Test
    fun aSpeedOnTheWalkBikeBoundaryDoesNotOscillate() =
        runTest {
            val drive = Drive()
            val host = RecordingMapHost()
            val controller = controller(host)
            controller.onState(following(drive.first()))
            controller.onState(following(drive.at(speedKmh = 6.9)))
            controller.onState(following(drive.at(speedKmh = 7.1)))
            controller.onState(following(drive.at(speedKmh = 6.9)))
            controller.onState(following(drive.at(speedKmh = 7.1)))
            controller.onState(following(drive.at(speedKmh = 8.1)))
            controller.onState(following(drive.at(speedKmh = 7.1)))
            controller.onState(following(drive.at(speedKmh = 6.9)))
            controller.onState(following(drive.at(speedKmh = 5.9)))

            assertEquals(listOf(17.0, 16.0, 17.0), host.zooms())
        }

    @Test
    fun aSpeedOnTheBikeCarBoundaryDoesNotOscillate() =
        runTest {
            val drive = Drive()
            val host = RecordingMapHost()
            val controller = controller(host)
            controller.onState(following(drive.first()))
            controller.onState(following(drive.at(speedKmh = 15.0)))
            controller.onState(following(drive.at(speedKmh = 20.1)))
            controller.onState(following(drive.at(speedKmh = 19.9)))
            controller.onState(following(drive.at(speedKmh = 20.1)))
            controller.onState(following(drive.at(speedKmh = 21.1)))
            controller.onState(following(drive.at(speedKmh = 20.1)))
            controller.onState(following(drive.at(speedKmh = 19.9)))
            controller.onState(following(drive.at(speedKmh = 18.9)))

            assertEquals(listOf(16.0, 15.0, 16.0), host.zooms())
        }

    @Test
    fun theSpeedComesFromConsecutiveFixesNotTheFixSpeedField() =
        runTest {
            val drive = Drive()
            val host = RecordingMapHost()
            val controller = controller(host)
            controller.onState(following(drive.first().copy(speedMetersPerSecond = CAR_MPS)))
            controller.onState(following(drive.at(speedKmh = 5.0).copy(speedMetersPerSecond = CAR_MPS)))

            assertEquals(listOf(17.0), host.zooms())
        }

    @Test
    fun theCameraRecentresAfterTheDelayAndThenFollowsAgain() =
        runTest {
            val drive = Drive()
            val clock = FakeClock()
            val host = RecordingMapHost()
            val controller = controller(host, clock)
            controller.onState(following(drive.first()))
            val initialCentres = host.centres()
            controller.onManualPan()

            clock.nowMillis = NavigationCameraController.RECENTRE_DELAY_MILLIS - 1L
            controller.onState(following(drive.at(speedKmh = 5.0)))
            assertEquals(initialCentres, host.centres())

            clock.nowMillis = NavigationCameraController.RECENTRE_DELAY_MILLIS
            val due = drive.at(speedKmh = 5.0)
            controller.onState(following(due))
            assertEquals(initialCentres + due.position, host.centres())

            val after = drive.at(speedKmh = 5.0)
            controller.onState(following(after))
            assertEquals(initialCentres + listOf(due.position, after.position), host.centres())
        }

    @Test
    fun aSecondPanRestartsTheRecentreDelay() =
        runTest {
            val drive = Drive()
            val clock = FakeClock()
            val host = RecordingMapHost()
            val controller = controller(host, clock)
            controller.onState(following(drive.first()))
            val initialCentres = host.centres()
            controller.onManualPan()

            clock.nowMillis = NavigationCameraController.RECENTRE_DELAY_MILLIS / 2L
            controller.onManualPan()

            clock.nowMillis = NavigationCameraController.RECENTRE_DELAY_MILLIS + 1L
            controller.onState(following(drive.at(speedKmh = 5.0)))
            assertEquals(initialCentres, host.centres())

            clock.nowMillis = NavigationCameraController.RECENTRE_DELAY_MILLIS * 2L
            val due = drive.at(speedKmh = 5.0)
            controller.onState(following(due))
            assertEquals(initialCentres + due.position, host.centres())
        }

    @Test
    fun tiltIsOnAtFortyFiveByDefaultThenOffAndOnAgain() =
        runTest {
            val drive = Drive()
            val host = RecordingMapHost()
            val controller = controller(host)
            assertTrue(controller.settings.value.tilt)
            controller.onState(following(drive.first()))
            assertEquals(listOf(NavigationCameraController.TILT_DEGREES), host.tilts())

            controller.setTilt(false)
            assertFalse(controller.settings.value.tilt)
            assertEquals(
                listOf(NavigationCameraController.TILT_DEGREES, NavigationCameraController.FLAT_DEGREES),
                host.tilts(),
            )

            val tiltsAfterOff = host.tilts()
            controller.onState(following(drive.at(speedKmh = 5.0)))
            assertEquals(tiltsAfterOff, host.tilts())

            controller.setTilt(true)
            assertEquals(
                listOf(
                    NavigationCameraController.TILT_DEGREES,
                    NavigationCameraController.FLAT_DEGREES,
                    NavigationCameraController.TILT_DEGREES,
                ),
                host.tilts(),
            )
        }

    @Test
    fun resettingAtTheEndFlattensTheCameraAndANewTripTiltsItAgain() =
        runTest {
            val drive = Drive()
            val host = RecordingMapHost()
            val controller = controller(host)
            controller.onState(following(drive.first()))
            assertEquals(listOf(NavigationCameraController.TILT_DEGREES), host.tilts())

            controller.resetCamera()
            assertEquals(
                listOf(NavigationCameraController.TILT_DEGREES, NavigationCameraController.FLAT_DEGREES),
                host.tilts(),
            )

            controller.onState(following(drive.at(speedKmh = 5.0)))
            assertEquals(
                listOf(
                    NavigationCameraController.TILT_DEGREES,
                    NavigationCameraController.FLAT_DEGREES,
                    NavigationCameraController.TILT_DEGREES,
                ),
                host.tilts(),
            )
        }

    @Test
    fun clearingThePanAfterARecentreFollowsTheNextFixAtOnce() =
        runTest {
            val drive = Drive()
            val clock = FakeClock()
            val host = RecordingMapHost()
            val controller = controller(host, clock)
            controller.onState(following(drive.first()))
            val initialCentres = host.centres()
            controller.onManualPan()

            clock.nowMillis = NavigationCameraController.RECENTRE_DELAY_MILLIS - 1L
            controller.onState(following(drive.at(speedKmh = 5.0)))
            assertEquals(initialCentres, host.centres())

            controller.clearPan()
            val due = drive.at(speedKmh = 5.0)
            controller.onState(following(due))
            assertEquals(initialCentres + due.position, host.centres())
        }

    @Test
    fun withAutoZoomOffTheZoomIsLeftAloneUntilTheSwitchIsOn() =
        runTest {
            val drive = Drive()
            val host = RecordingMapHost()
            val controller = controller(host, settings = NavigationCameraSettings(autoZoom = false))
            controller.onState(following(drive.first()))
            controller.onState(following(drive.at(speedKmh = 5.0)))
            controller.onState(following(drive.at(speedKmh = 30.0)))

            assertTrue(host.zooms().isEmpty())

            controller.setAutoZoom(true)
            controller.onState(following(drive.at(speedKmh = 30.0)))
            assertEquals(listOf(15.0), host.zooms())
        }

    @Test
    fun withAutoCentreOffTheCameraIsNotMovedAfterAPan() =
        runTest {
            val drive = Drive()
            val clock = FakeClock()
            val host = RecordingMapHost()
            val controller =
                controller(host, clock, settings = NavigationCameraSettings(autoCentre = false))
            controller.onState(following(drive.first()))
            val initialCentres = host.centres()
            controller.onManualPan()
            clock.nowMillis = NavigationCameraController.RECENTRE_DELAY_MILLIS * 10L

            controller.onState(following(drive.at(speedKmh = 5.0)))
            controller.onState(following(drive.at(speedKmh = 5.0)))

            assertEquals(initialCentres, host.centres())

            controller.setAutoCentre(true)
            val after = drive.at(speedKmh = 5.0)
            controller.onState(following(after))
            assertEquals(initialCentres + after.position, host.centres())
        }

    @Test
    fun aPauseStartsANewSpeedIntervalSoTheGapDoesNotReadAsSlow() =
        runTest {
            val drive = Drive()
            val host = RecordingMapHost()
            val controller = controller(host)
            controller.onState(following(drive.first()))
            controller.onState(following(drive.at(speedKmh = 30.0)))
            assertEquals(listOf(15.0), host.zooms())

            controller.onState(NavigationState(phase = NavigationPhase.Paused, fix = drive.idle(seconds = 600L)))
            controller.onState(following(drive.at(speedKmh = 30.0)))
            controller.onState(following(drive.at(speedKmh = 30.0)))

            assertEquals(listOf(15.0), host.zooms())
        }

    @Test
    fun aCameraBoundAfterTheFirstFixesCatchesUpOnTheNextOne() =
        runTest {
            val drive = Drive()
            var bound: RecordingMapHost? = null
            val controller = NavigationCameraController(host = { bound }, clockMillis = { 0L })
            controller.onState(following(drive.first()))
            controller.onState(following(drive.at(speedKmh = 5.0)))

            val host = RecordingMapHost()
            bound = host
            val next = drive.at(speedKmh = 5.0)
            controller.onState(following(next))

            assertEquals(listOf(next.position), host.centres())
            assertEquals(listOf(17.0), host.zooms())
        }

    @Test
    fun aRejectedCameraMoveIsRetriedOnTheNextFix() =
        runTest {
            val drive = Drive()
            val host = RecordingMapHost()
            val controller = controller(host)
            controller.onState(following(drive.first()))
            host.failNextMove = true

            controller.onState(following(drive.at(speedKmh = 5.0)))
            assertTrue(host.zooms().isEmpty())

            controller.onState(following(drive.at(speedKmh = 5.0)))
            assertEquals(listOf(17.0), host.zooms())
        }

    @Test
    fun phasesWithoutGuidanceDoNotMoveTheCamera() =
        runTest {
            val drive = Drive()
            val host = RecordingMapHost()
            val controller = controller(host)
            controller.onState(following(drive.first()))
            val before = host.updates.size

            controller.onState(NavigationState(phase = NavigationPhase.Following))
            for (phase in listOf(NavigationPhase.Idle, NavigationPhase.Paused, NavigationPhase.Arrived, NavigationPhase.Stopped)) {
                controller.onState(NavigationState(phase = phase, fix = drive.at(speedKmh = 30.0)))
            }

            assertEquals(before, host.updates.size)
        }

    private fun controller(
        host: RecordingMapHost,
        clock: FakeClock = FakeClock(),
        settings: NavigationCameraSettings = NavigationCameraSettings(),
    ): NavigationCameraController =
        NavigationCameraController(
            host = { host },
            clockMillis = { clock.nowMillis },
            settings = settings,
        )

    private fun following(fix: LocationFix): NavigationState = NavigationState(phase = NavigationPhase.Following, fix = fix)

    private fun rerouting(fix: LocationFix): NavigationState = NavigationState(phase = NavigationPhase.Rerouting, fix = fix)

    private class Drive {
        private var position: GeoPoint = START
        private var timeMillis: Long = 0L

        fun first(): LocationFix = fix()

        fun at(speedKmh: Double): LocationFix {
            val meters = speedKmh / CameraSpeedTable.KMH_PER_MPS * SECONDS
            position = GeoMath.offset(position, EAST, meters)
            timeMillis += SECOND_MILLIS
            return fix(speedKmh)
        }

        /** Advances the clock without moving, as a paused session does. */
        fun idle(seconds: Long): LocationFix {
            timeMillis += seconds * SECOND_MILLIS
            return fix()
        }

        private fun fix(speedKmh: Double? = null): LocationFix =
            LocationFix(
                position = position,
                accuracyMeters = 5.0,
                bearingDegrees = EAST,
                speedMetersPerSecond = speedKmh?.div(CameraSpeedTable.KMH_PER_MPS),
                timeMillis = timeMillis,
            )
    }

    private class FakeClock {
        var nowMillis: Long = 0L
    }

    private class RecordingMapHost : MapHost {
        private val state = MutableStateFlow(Camera(center = START, zoom = 14.0))
        val updates = mutableListOf<CameraUpdate>()
        var failNextMove: Boolean = false

        override val camera: StateFlow<Camera> = state
        override val events: Flow<MapEvent> = emptyFlow()

        override suspend fun setStyle(style: StyleSpec): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun applyStyleDiff(diff: StyleDiff): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun addLayer(
            slot: LayerSlot,
            spec: LayerSpec,
            owner: PluginId,
        ): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun removeLayers(owner: PluginId): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun removeLayer(layer: LayerId): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun visibleBounds(): BoundingBox? = null

        override suspend fun moveCamera(update: CameraUpdate): Outcome<Unit> = record(update)

        override suspend fun animateCamera(update: CameraUpdate): Outcome<Unit> = record(update)

        override suspend fun fitBounds(bounds: BoundingBox): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setTrackingMode(mode: TrackingMode): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setLocation(fix: LocationFix?): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setRoute(route: Route?): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setTracks(tracks: List<MapTrack>): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setPositionOffset(offset: ScreenOffset): Outcome<Unit> = Outcome.Success(Unit)

        fun zooms(): List<Double> = updates.mapNotNull { it.zoom }

        fun centres(): List<GeoPoint> = updates.mapNotNull { it.center }

        fun tilts(): List<Double> = updates.mapNotNull { it.tilt }

        private fun record(update: CameraUpdate): Outcome<Unit> {
            if (failNextMove) {
                failNextMove = false
                return Outcome.Failure(AppError.NotReady)
            }
            updates += update
            state.value =
                state.value.copy(
                    center = update.center ?: state.value.center,
                    zoom = update.zoom ?: state.value.zoom,
                    bearing = update.bearing ?: state.value.bearing,
                    tilt = update.tilt ?: state.value.tilt,
                )
            return Outcome.Success(Unit)
        }
    }

    private companion object {
        val START = GeoPoint(latitude = 49.8419, longitude = 24.0316)
        const val EAST: Double = 90.0
        const val SECONDS: Double = 1.0
        const val SECOND_MILLIS: Long = 1_000L
        const val CAR_MPS: Double = 30.0 / CameraSpeedTable.KMH_PER_MPS
    }
}
