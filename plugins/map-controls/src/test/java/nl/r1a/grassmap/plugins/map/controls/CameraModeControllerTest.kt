package nl.r1a.grassmap.plugins.map.controls

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
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.model.TrackingMode
import nl.r1a.grassmap.plugin.api.provider.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraModeControllerTest {
    @Test
    fun theCycleIsFreeThenFollowThenHeadingThenFree() =
        runTest {
            val host = RecordingMapHost()
            val controller = controller(host)

            assertEquals(Outcome.Success(CameraMode.Follow), controller.cycle())
            assertEquals(Outcome.Success(CameraMode.Heading), controller.cycle())
            assertEquals(Outcome.Success(CameraMode.Free), controller.cycle())

            assertEquals(
                listOf(
                    TrackingMode.Follow,
                    TrackingMode.FollowWithHeading,
                    TrackingMode.Free,
                ),
                host.trackingModes,
            )
        }

    @Test
    fun freeLeavesFollowWithoutMovingToTheFix() =
        runTest {
            val host = RecordingMapHost()
            val controller = controller(host)
            controller.cycle()
            controller.onFix(fix(bearingDegrees = null, speedMetersPerSecond = 1.0))
            val movesBeforeFree = host.moves.size

            assertEquals(Outcome.Success(CameraMode.Free), controller.free())

            assertEquals(CameraMode.Free, controller.mode.value)
            assertEquals(TrackingMode.Free, host.trackingModes.last())
            assertEquals(movesBeforeFree, host.moves.size)
        }

    @Test
    fun freeWithoutABoundHostIsNotReady() =
        runTest {
            val controller = CameraModeController(host = { null }, store = CameraModeStore())

            assertEquals(Outcome.Failure(AppError.NotReady), controller.free())
        }

    @Test
    fun headingUsesTheLocationBearingWhileMoving() =
        runTest {
            val host = RecordingMapHost()
            val controller = controller(host)
            controller.cycle()
            controller.cycle()

            controller.onFix(fix(bearingDegrees = 90.0, speedMetersPerSecond = 2.0))

            assertEquals(
                CameraUpdate(center = POSITION, bearing = 90.0),
                host.moves.last(),
            )
        }

    @Test
    fun followRecentersOnTheFirstFixOnly() =
        runTest {
            val host = RecordingMapHost()
            val controller = controller(host)
            controller.cycle()

            controller.onFix(fix(bearingDegrees = null, speedMetersPerSecond = 1.0))
            assertEquals(CameraUpdate(center = POSITION), host.moves.last())

            val movesAfterFirstFix = host.moves.size
            controller.onFix(fix(bearingDegrees = null, speedMetersPerSecond = 1.0))

            assertEquals(movesAfterFirstFix, host.moves.size)
        }

    @Test
    fun headingUpdatesOnlyTheBearingAfterTheFirstFix() =
        runTest {
            val host = RecordingMapHost()
            val controller = controller(host)
            controller.cycle()
            controller.cycle()
            controller.onFix(fix(bearingDegrees = 90.0, speedMetersPerSecond = 2.0))

            controller.onFix(fix(bearingDegrees = 100.0, speedMetersPerSecond = 2.0))

            assertEquals(CameraUpdate(bearing = 100.0), host.moves.last())
        }

    @Test
    fun headingUsesTheCompassWhenTheFixHasNoTravelBearing() =
        runTest {
            val host = RecordingMapHost()
            val controller = controller(host, headingDegrees = 45.0)
            controller.cycle()
            controller.cycle()

            controller.onFix(fix(bearingDegrees = 90.0, speedMetersPerSecond = 0.0))

            assertEquals(
                CameraUpdate(center = POSITION, bearing = 45.0),
                host.moves.last(),
            )
        }

    @Test
    fun headingFallsBackToFollowWhenNoBearingExists() =
        runTest {
            val host = RecordingMapHost()
            val controller = controller(host, headingDegrees = null)
            controller.cycle()
            controller.cycle()

            controller.onFix(fix(bearingDegrees = null, speedMetersPerSecond = 0.0))

            assertEquals(CameraMode.Follow, controller.mode.value)
            assertEquals(TrackingMode.Follow, host.trackingModes.last())
            assertEquals(CameraUpdate(center = POSITION), host.moves.last())
        }

    @Test
    fun aCompassTapSetsTheBearingToZero() =
        runTest {
            val host = RecordingMapHost()
            val controller = controller(host)

            assertEquals(Outcome.Success(0.0), controller.resetNorth())

            assertEquals(CameraUpdate(bearing = 0.0), host.moves.last())
        }

    @Test
    fun aCompassTapLeavesHeadingForFollow() =
        runTest {
            val host = RecordingMapHost()
            val controller = controller(host)
            controller.cycle()
            controller.cycle()

            controller.resetNorth()

            assertEquals(CameraMode.Follow, controller.mode.value)
            assertEquals(TrackingMode.Follow, host.trackingModes.last())
        }

    @Test
    fun aModeChangeWithoutABoundHostIsNotReady() =
        runTest {
            val controller = CameraModeController({ null })

            assertEquals(Outcome.Failure(AppError.NotReady), controller.cycle())
            assertEquals(Outcome.Failure(AppError.NotReady), controller.resetNorth())
        }

    private fun controller(
        host: RecordingMapHost,
        headingDegrees: Double? = null,
    ): CameraModeController =
        CameraModeController(
            host = { host },
            heading = MutableStateFlow(headingDegrees),
            store = CameraModeStore(),
        )

    private class RecordingMapHost : MapHost {
        private val state = MutableStateFlow(Camera(center = ORIGIN, zoom = 15.0))
        val moves = mutableListOf<CameraUpdate>()
        val trackingModes = mutableListOf<TrackingMode>()

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

        override suspend fun moveCamera(update: CameraUpdate): Outcome<Unit> {
            moves += update
            state.value =
                state.value.copy(
                    center = update.center ?: state.value.center,
                    bearing = update.bearing ?: state.value.bearing,
                )
            return Outcome.Success(Unit)
        }

        override suspend fun animateCamera(update: CameraUpdate): Outcome<Unit> = moveCamera(update)

        override suspend fun fitBounds(bounds: BoundingBox): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setTrackingMode(mode: TrackingMode): Outcome<Unit> {
            trackingModes += mode
            return Outcome.Success(Unit)
        }

        override suspend fun setLocation(fix: LocationFix?): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setRoute(route: Route?): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setPositionOffset(offset: ScreenOffset): Outcome<Unit> = Outcome.Success(Unit)
    }

    private companion object {
        val ORIGIN = GeoPoint(latitude = 49.84, longitude = 24.03)
        val POSITION = GeoPoint(latitude = 49.841, longitude = 24.031)

        fun fix(
            bearingDegrees: Double?,
            speedMetersPerSecond: Double,
        ): LocationFix =
            LocationFix(
                position = POSITION,
                accuracyMeters = 5.0,
                bearingDegrees = bearingDegrees,
                speedMetersPerSecond = speedMetersPerSecond,
                timeMillis = 1_000L,
            )
    }
}
