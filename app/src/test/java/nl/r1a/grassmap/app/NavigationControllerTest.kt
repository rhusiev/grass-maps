package nl.r1a.grassmap.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.navigation.GeometryRouteFollower
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationControllerTest {
    @Test
    fun startingTwiceKeepsOneSessionAndOneServiceStart() =
        runTest {
            val harness = harness(backgroundScope)

            assertTrue(harness.controller.start(ROUTE, RouteProfile.Walk).valueOrFail())
            assertFalse(harness.controller.start(ROUTE, RouteProfile.Walk).valueOrFail())
            runCurrent()

            assertEquals(1, harness.service.starts)
            assertTrue(harness.controller.isActive)

            harness.controller.stop()
            runCurrent()
            advanceUntilIdle()

            assertEquals(NavigationPhase.Stopped, harness.controller.state.value.phase)
            assertEquals(1, harness.service.stops)
            assertFalse(harness.controller.isActive)
        }

    @Test
    fun stoppingWithoutASessionChangesNothing() =
        runTest {
            val harness = harness(backgroundScope)

            assertFalse(harness.controller.stop())
            advanceUntilIdle()

            assertEquals(0, harness.service.starts)
            assertEquals(0, harness.service.stops)
            assertEquals(NavigationPhase.Idle, harness.controller.state.value.phase)
        }

    @Test
    fun aMissingPermissionStartsNothing() =
        runTest {
            val harness = harness(backgroundScope, hasPermission = false, requiresPermission = true)

            val outcome = harness.controller.start(ROUTE, RouteProfile.Walk)

            assertEquals(Outcome.Failure(AppError.PermissionDenied), outcome)
            assertEquals(0, harness.service.starts)
            assertEquals(NavigationPhase.Idle, harness.controller.state.value.phase)
        }

    @Test
    fun pausingBeforeAFixShowsThePausedPhaseAndTheFixIsNotApplied() =
        runTest {
            val harness = harness(backgroundScope)
            harness.controller.start(ROUTE, RouteProfile.Walk).valueOrFail()
            runCurrent()

            assertTrue(harness.controller.pause())
            runCurrent()

            assertEquals(NavigationPhase.Paused, harness.controller.state.value.phase)

            harness.fixes.emit(fix(START))
            runCurrent()

            assertEquals(NavigationPhase.Paused, harness.controller.state.value.phase)
            assertNull(harness.controller.state.value.fix)

            harness.controller.stop()
            runCurrent()
            advanceUntilIdle()
        }

    @Test
    fun pauseWorksWhileTheEngineIsBeingResolved() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val harness =
                harness(
                    backgroundScope,
                    routing = {
                        release.await()
                        null
                    },
                )
            harness.controller.start(ROUTE, RouteProfile.Walk).valueOrFail()
            runCurrent()

            assertTrue(harness.controller.pause())
            release.complete(Unit)
            runCurrent()

            assertEquals(NavigationPhase.Paused, harness.controller.state.value.phase)
            harness.controller.stop()
            runCurrent()
            advanceUntilIdle()
        }

    @Test
    fun aSessionEndingWhilePausedStopsTheService() =
        runTest {
            val harness = harness(backgroundScope)
            harness.controller.start(ROUTE, RouteProfile.Walk).valueOrFail()
            runCurrent()
            harness.fixes.emit(fix(START))
            runCurrent()
            assertEquals(NavigationPhase.Following, harness.controller.state.value.phase)

            harness.controller.pause()
            runCurrent()
            assertEquals(NavigationPhase.Paused, harness.controller.state.value.phase)

            harness.controller.stop()
            runCurrent()
            advanceUntilIdle()

            assertEquals(NavigationPhase.Stopped, harness.controller.state.value.phase)
            assertEquals(1, harness.service.stops)
            assertFalse(harness.controller.isActive)
        }

    @Test
    fun arrivalEndsTheSessionAndStopsTheService() =
        runTest {
            val harness = harness(backgroundScope)
            harness.controller.start(ROUTE, RouteProfile.Walk).valueOrFail()
            runCurrent()
            harness.fixes.emit(fix(START))
            runCurrent()
            harness.fixes.emit(fix(END))
            runCurrent()
            // The takeWhile checks the arrival flag on the next upstream item.
            harness.fixes.emit(fix(END))
            runCurrent()
            advanceUntilIdle()

            assertEquals(NavigationPhase.Arrived, harness.controller.state.value.phase)
            assertEquals(1, harness.service.stops)
            assertFalse(harness.controller.isActive)
        }

    private fun harness(
        scope: CoroutineScope,
        hasPermission: Boolean = true,
        requiresPermission: Boolean = false,
        routing: suspend () -> RoutingEngine? = { null },
    ): Harness {
        val fixes = MutableSharedFlow<LocationFix?>(extraBufferCapacity = FIX_BUFFER)
        val service = FakeServiceHandle()
        val controller =
            NavigationController(
                scope = scope,
                fixes = fixes,
                hasLocationPermission = { hasPermission },
                requiresLocationPermission = { requiresPermission },
                routing = routing,
                service = service,
                followerFactory = { GeometryRouteFollower() },
            )
        return Harness(controller, service, fixes)
    }

    private class Harness(
        val controller: NavigationController,
        val service: FakeServiceHandle,
        val fixes: MutableSharedFlow<LocationFix?>,
    )

    private class FakeServiceHandle : NavigationServiceHandle {
        var starts: Int = 0
            private set
        var stops: Int = 0
            private set

        override fun start() {
            starts += 1
        }

        override fun stop() {
            stops += 1
        }
    }

    private fun <T> Outcome<T>.valueOrFail(): T =
        when (this) {
            is Outcome.Success -> value
            is Outcome.Failure -> throw AssertionError("expected success, got $error")
        }

    private companion object {
        const val FIX_BUFFER = 8

        val START = GeoPoint(49.8400, 24.0300)
        val END = GeoPoint(49.8420, 24.0300)
        val ROUTE =
            Route(
                geometry = listOf(START, END),
                distanceMeters = 222.0,
                durationSeconds = 200.0,
            )

        fun fix(point: GeoPoint): LocationFix = LocationFix(position = point, accuracyMeters = 5.0, timeMillis = 1_000L)
    }
}
