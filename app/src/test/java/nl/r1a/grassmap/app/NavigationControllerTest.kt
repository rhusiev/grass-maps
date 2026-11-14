package nl.r1a.grassmap.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.navigation.GeometryRouteFollower
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.RouteRelation
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
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
    fun arrivalEndsTheSessionAndKeepsTheNotificationUntilDismissed() =
        runTest {
            val harness = harness(backgroundScope)
            harness.controller.start(ROUTE, RouteProfile.Walk).valueOrFail()
            runCurrent()
            harness.fixes.emit(fix(START))
            runCurrent()
            // The fix at the destination is the last one the session needs: no
            // later fix tells it that the trip ended.
            harness.fixes.emit(fix(END))
            runCurrent()
            advanceUntilIdle()

            assertEquals(NavigationPhase.Arrived, harness.controller.state.value.phase)
            assertEquals(0, harness.service.stops)
            assertFalse(harness.controller.isActive)

            harness.controller.dismiss()
            assertEquals(NavigationPhase.Idle, harness.controller.state.value.phase)
            assertEquals(1, harness.service.stops)
        }

    @Test
    fun stoppingDuringARerouteReleasesTheScreenAndNotificationAtOnce() =
        runTest {
            val router = UninterruptibleRouter()
            val harness = harness(backgroundScope, routing = { router })
            harness.controller.start(ROUTE, RouteProfile.Walk).valueOrFail()
            runCurrent()
            harness.fixes.emit(fix(OFF_ROUTE, FIRST_FIX_MILLIS))
            runCurrent()
            harness.fixes.emit(fix(OFF_ROUTE_2, SECOND_FIX_MILLIS))
            runCurrent()
            assertTrue("the reroute asks the engine", router.entered.isCompleted)

            assertTrue(harness.controller.stop())

            // The native call has not answered, so the session body is still
            // inside it; End must not wait for that answer.
            assertFalse("the engine is still routing", router.answered.isCompleted)
            assertEquals(NavigationPhase.Stopped, harness.controller.state.value.phase)
            assertEquals(1, harness.service.stops)
            assertFalse(harness.controller.isActive)

            router.release.complete(Unit)
            advanceUntilIdle()

            // The late answer must neither reopen the screen nor stop the
            // service a second time.
            assertEquals(NavigationPhase.Stopped, harness.controller.state.value.phase)
            assertEquals(1, harness.service.stops)
            assertEquals(1, router.requests)
        }

    @Test
    fun dismissLeavesARunningSessionAlone() =
        runTest {
            val harness = harness(backgroundScope)
            harness.controller.start(ROUTE, RouteProfile.Walk).valueOrFail()
            runCurrent()

            harness.controller.dismiss()

            assertEquals(NavigationPhase.Following, harness.controller.state.value.phase)
            harness.controller.stop()
            runCurrent()
            advanceUntilIdle()
        }

    @Test
    fun aFollowedTrackIsNotReroutedWhenTheUserIsStillWalkingToIt() =
        runTest {
            val router = CountingRouter()
            val harness = harness(backgroundScope, routing = { router })
            harness.controller.start(ROUTE, RouteProfile.Walk, reroute = false).valueOrFail()
            runCurrent()
            // Two fixes well off the line, 100 m apart, so the off-route
            // confirmation and the gate's travel budget are both spent.
            harness.fixes.emit(fix(OFF_ROUTE))
            runCurrent()
            harness.fixes.emit(fix(OFF_ROUTE_2))
            runCurrent()
            advanceUntilIdle()

            assertEquals(0, router.requests)
            assertEquals(RouteRelation.OffRoute, harness.controller.state.value.relation)
            assertTrue(harness.controller.isActive)
            harness.controller.stop()
            runCurrent()
            advanceUntilIdle()
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

    /** Counts the routes it is asked for; a followed track must ask for none. */
    private class CountingRouter : RoutingEngine {
        var requests: Int = 0
            private set

        override val contract: ProviderContract = RoutingEngineContract.REFERENCE

        override fun capabilities(): RoutingCapabilities =
            RoutingCapabilities(
                profiles = setOf(RouteProfile.Walk),
                modifierKinds = emptySet(),
                maxAlternatives = 0,
                maxDistanceMeters = 250_000.0,
            )

        override suspend fun route(
            request: RouteRequest,
            cancellation: CancellationHandle,
        ): Outcome<List<Route>> {
            requests += 1
            return Outcome.Failure(AppError.RoutingPackUnavailable(AppError.RoutingPackUnavailable.Reason.Absent))
        }
    }

    /**
     * Stands in for the native Valhalla call: it reports that routing started,
     * then waits in [NonCancellable] until the test answers. Cancelling the
     * session therefore cannot complete its body, which is the shape of a
     * blocking native call the engine can only check cancellation around.
     */
    private class UninterruptibleRouter : RoutingEngine {
        val entered = CompletableDeferred<Unit>()
        val answered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests: Int = 0
            private set

        override val contract: ProviderContract = RoutingEngineContract.REFERENCE

        override fun capabilities(): RoutingCapabilities =
            RoutingCapabilities(
                profiles = setOf(RouteProfile.Walk),
                modifierKinds = emptySet(),
                maxAlternatives = 0,
                maxDistanceMeters = 250_000.0,
            )

        override suspend fun route(
            request: RouteRequest,
            cancellation: CancellationHandle,
        ): Outcome<List<Route>> {
            requests += 1
            entered.complete(Unit)
            withContext(NonCancellable) {
                release.await()
                answered.complete(Unit)
            }
            return Outcome.Failure(AppError.NotReady)
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

        /** 200 m east of the line, then 100 m along it while still off route. */
        val OFF_ROUTE = GeoPoint(49.8400, 24.0328)
        val OFF_ROUTE_2 = GeoPoint(49.8409, 24.0328)

        /**
         * 45 s apart: 100 m in that time is 2.2 m/s, under the gate's four-times
         * walking-speed cap, so the second fix spends the reroute budget.
         */
        const val FIRST_FIX_MILLIS = 1_000L
        const val SECOND_FIX_MILLIS = 46_000L
        val ROUTE =
            Route(
                geometry = listOf(START, END),
                distanceMeters = 222.0,
                durationSeconds = 200.0,
            )

        fun fix(
            point: GeoPoint,
            timeMillis: Long = 1_000L,
        ): LocationFix = LocationFix(position = point, accuracyMeters = 5.0, timeMillis = timeMillis)
    }
}
