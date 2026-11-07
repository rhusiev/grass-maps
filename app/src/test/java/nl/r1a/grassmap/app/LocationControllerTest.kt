package nl.r1a.grassmap.app

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.core.services.LocationSource
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.TrackingMode
import nl.r1a.grassmap.plugins.map.controls.CameraModeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationControllerTest {
    @Test
    fun followingRecentersOnceAndLeavesInterpolationToTheFrameLoop() =
        runTest {
            val host = FakeMapHost(camera())
            val session = MapSessionHolder().apply { bind(host) }
            val source = FakeLocationSource()
            val controller = LocationController(source, backgroundScope, CameraModeController(session::hostOrNull))
            controller.start()
            runCurrent()
            assertEquals(Outcome.Success(true), controller.toggleFollowing())

            source.emit(FIX_ONE)

            runCurrent()
            source.emit(FIX_TWO)

            runCurrent()

            assertEquals(listOf(FIX_ONE, FIX_TWO), host.locations)
            assertEquals(listOf(CameraUpdate(center = FIX_ONE.position)), host.moves)
            assertEquals(listOf(TrackingMode.Follow), host.trackingModes)
        }

    @Test
    fun freeSetsThePuckWithoutMovingTheCamera() =
        runTest {
            val host = FakeMapHost(camera())
            val session = MapSessionHolder().apply { bind(host) }
            val source = FakeLocationSource()
            val controller = LocationController(source, backgroundScope, CameraModeController(session::hostOrNull))
            controller.start()
            runCurrent()

            source.emit(FIX_ONE)

            runCurrent()

            assertEquals(listOf(FIX_ONE), host.locations)
            assertTrue(host.moves.isEmpty())
        }

    @Test
    fun togglingFollowOffStopsRecenteringButKeepsThePuckCurrent() =
        runTest {
            val host = FakeMapHost(camera())
            val session = MapSessionHolder().apply { bind(host) }
            val source = FakeLocationSource()
            val controller = LocationController(source, backgroundScope, CameraModeController(session::hostOrNull))
            controller.start()
            runCurrent()
            controller.toggleFollowing()

            assertEquals(Outcome.Success(false), controller.toggleFollowing())
            source.emit(FIX_TWO)

            runCurrent()

            assertEquals(emptyList<CameraUpdate>(), host.moves)
            assertEquals(listOf(FIX_TWO), host.locations)
            assertEquals(listOf(TrackingMode.Follow, TrackingMode.Free), host.trackingModes)
        }

    @Test
    fun followingOnRecentersToTheLatestFixAtOnce() =
        runTest {
            val host = FakeMapHost(camera())
            val session = MapSessionHolder().apply { bind(host) }
            val source = FakeLocationSource()
            val controller = LocationController(source, backgroundScope, CameraModeController(session::hostOrNull))
            controller.start()
            runCurrent()
            source.emit(FIX_ONE)

            runCurrent()

            controller.toggleFollowing()

            assertEquals(listOf(CameraUpdate(center = FIX_ONE.position)), host.moves)
        }

    @Test
    fun aNullFixClearsThePuck() =
        runTest {
            val host = FakeMapHost(camera())
            val session = MapSessionHolder().apply { bind(host) }
            val source = FakeLocationSource()
            val controller = LocationController(source, backgroundScope, CameraModeController(session::hostOrNull))
            controller.start()
            runCurrent()
            source.emit(FIX_ONE)

            runCurrent()

            source.emit(null)

            runCurrent()

            assertEquals(listOf(FIX_ONE, null), host.locations)
        }

    @Test
    fun togglingWithoutABoundHostIsNotReady() =
        runTest {
            val controller =
                LocationController(
                    FakeLocationSource(),
                    backgroundScope,
                    CameraModeController({ null }),
                )

            assertEquals(Outcome.Failure(AppError.NotReady), controller.toggleFollowing())
        }

    @Test
    fun syncingToANewHostPushesTheLatestFixAndFollows() =
        runTest {
            val source = FakeLocationSource()
            val session = MapSessionHolder()
            val controller = LocationController(source, backgroundScope, CameraModeController(session::hostOrNull))
            controller.start()
            runCurrent()
            source.emit(FIX_ONE)

            runCurrent()
            val host = FakeMapHost(camera())
            session.bind(host)
            controller.syncToHost()
            controller.toggleFollowing()

            assertEquals(listOf(FIX_ONE), host.locations)
            assertEquals(listOf(CameraUpdate(center = FIX_ONE.position)), host.moves)
        }

    @Test
    fun startingTwiceCollectsTheSourceOnce() =
        runTest {
            val host = FakeMapHost(camera())
            val session = MapSessionHolder().apply { bind(host) }
            val source = FakeLocationSource()
            val controller = LocationController(source, backgroundScope, CameraModeController(session::hostOrNull))
            controller.start()
            runCurrent()
            controller.start()
            runCurrent()

            source.emit(FIX_ONE)

            runCurrent()

            assertEquals(listOf(FIX_ONE), host.locations)
        }

    @Test
    fun aRepeatedPermissionGrantDoesNotRestartTheCollector() =
        runTest {
            val host = FakeMapHost(camera())
            val session = MapSessionHolder().apply { bind(host) }
            val source = ReplayingLocationSource()
            val controller = LocationController(source, session, backgroundScope)
            controller.start()
            runCurrent()

            controller.onPermissionGranted()
            runCurrent()

            assertEquals(1, source.collections)
            assertEquals(listOf(FIX_ONE), host.locations)
        }

    private class ReplayingLocationSource : LocationSource {
        var collections: Int = 0
            private set

        override val fixes: Flow<LocationFix?> =
            flow {
                collections += 1
                emit(FIX_ONE)
                awaitCancellation()
            }

        override fun hasPermission(): Boolean = true
    }

    private class FakeLocationSource : LocationSource {
        private val values = MutableSharedFlow<LocationFix?>()
        override val fixes: Flow<LocationFix?> = values

        override fun hasPermission(): Boolean = true

        suspend fun emit(fix: LocationFix?) {
            values.emit(fix)
        }
    }

    private companion object {
        fun camera(): Camera = Camera(center = ORIGIN, zoom = 15.0)

        val ORIGIN = GeoPoint(latitude = 49.84, longitude = 24.03)
        val FIX_ONE = fix(latitude = 49.841, longitude = 24.031)
        val FIX_TWO = fix(latitude = 49.842, longitude = 24.032)

        fun fix(
            latitude: Double,
            longitude: Double,
        ): LocationFix =
            LocationFix(
                position = GeoPoint(latitude = latitude, longitude = longitude),
                accuracyMeters = 5.0,
                timeMillis = 1_000L,
            )
    }
}
