package nl.r1a.grassmap.navigation

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pause and resume through the real session and the geometry follower.
 *
 * The fix source is a channel so the test can hold the session between fixes
 * and change the pause flag with no fix in flight, which is what the pause
 * button does.
 */
class NavigationPauseTest {
    @Test
    fun pauseHoldsGuidanceAndResumeContinuesFromTheNextFix() =
        runTest {
            val fixes = Channel<LocationFix>(Channel.UNLIMITED)
            val session = session()
            val run = launch { session.run(ROUTE, fixes.receiveAsFlow()) }
            fixes.send(fix(0, SOUTH))

            runCurrent()

            val following = session.state.value
            assertEquals(NavigationPhase.Following, following.phase)
            assertTrue(following.remainingDistanceMeters > 0.0)

            session.control.pause()

            runCurrent()

            assertEquals(NavigationPhase.Paused, session.state.value.phase)
            assertEquals(following.fix, session.state.value.fix)

            fixes.send(fix(1, MIDDLE))

            runCurrent()

            assertEquals(NavigationPhase.Paused, session.state.value.phase)
            assertEquals(following.fix, session.state.value.fix)
            assertEquals(following.remainingDistanceMeters, session.state.value.remainingDistanceMeters, 0.0)

            session.control.resume()

            runCurrent()

            assertEquals(NavigationPhase.Following, session.state.value.phase)
            assertEquals(
                "a fix from the pause is not applied on resume",
                following.fix,
                session.state.value.fix,
            )

            fixes.send(fix(2, MIDDLE))

            runCurrent()

            assertTrue(
                "the resumed fix moves the guidance",
                session.state.value.remainingDistanceMeters < following.remainingDistanceMeters,
            )
            fixes.close()
            run.join()
        }

    @Test
    fun pauseAfterArrivalKeepsTheTerminalPhase() =
        runTest {
            val fixes = Channel<LocationFix>(Channel.UNLIMITED)
            val session = session()
            val run = launch { session.run(ROUTE, fixes.receiveAsFlow()) }
            fixes.send(fix(0, SOUTH))
            fixes.send(fix(1, END))

            runCurrent()

            assertEquals(NavigationPhase.Arrived, session.state.value.phase)

            session.control.pause()

            runCurrent()

            assertEquals(
                "a pause after arrival must not replace the terminal phase",
                NavigationPhase.Arrived,
                session.state.value.phase,
            )

            session.control.resume()

            runCurrent()

            assertEquals(
                "a resume after arrival must not restore guidance",
                NavigationPhase.Arrived,
                session.state.value.phase,
            )

            fixes.send(fix(2, END))

            runCurrent()
            run.join()
            assertEquals(NavigationPhase.Arrived, session.state.value.phase)
        }

    @Test
    fun pausedFixesCannotStartARerouteAndTheSameFixesDoAfterResume() =
        runTest {
            val fixes = Channel<LocationFix>(Channel.UNLIMITED)
            val router = CountingRouter()
            val session =
                NavigationSession(
                    follower = GeometryRouteFollower(),
                    routing = router,
                    profile = RouteProfile.Walk,
                    config = NavigationConfig(maxDeviationMeters = 35.0, offRouteConfirmFixes = 2),
                    log = {},
                )
            val run = launch { session.run(ROUTE, fixes.receiveAsFlow()) }
            fixes.send(fix(0, SOUTH))

            runCurrent()

            session.control.pause()

            runCurrent()

            val offRoute =
                (0 until OFF_ROUTE_FIXES).map { index ->
                    fix(index + 1, GeoPoint(OFF_ROUTE_LATITUDE + index * OFF_ROUTE_STEP_DEGREES, OFF_ROUTE_LONGITUDE))
                }
            offRoute.forEach { fix ->
                fixes.send(fix)
                runCurrent()
            }

            assertEquals("a paused session must not reroute", 0, router.requests)
            assertEquals(NavigationPhase.Paused, session.state.value.phase)

            session.control.resume()

            runCurrent()

            offRoute.forEach { fix ->
                fixes.send(fix)
                runCurrent()
            }

            assertTrue("the resumed off-route fixes reach the gate", router.requests > 0)
            fixes.close()
            run.join()
        }

    private fun session(): NavigationSession =
        NavigationSession(
            follower = GeometryRouteFollower(),
            routing = NoRoutingEngine,
            profile = RouteProfile.Walk,
            config = NavigationConfig(reroute = false),
        )

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
            return Outcome.Failure(nl.r1a.grassmap.plugin.api.error.AppError.NotReady)
        }
    }

    private companion object {
        val SOUTH = GeoPoint(49.8400, 24.0300)
        val MIDDLE = GeoPoint(49.8430, 24.0300)
        val END = GeoPoint(49.8460, 24.0300)

        /** Fixes about 70 m east of the line, each 2.5 m further along it. */
        const val OFF_ROUTE_LATITUDE = 49.8410
        const val OFF_ROUTE_LONGITUDE = 24.0310
        const val OFF_ROUTE_STEP_DEGREES = 0.0000225
        const val OFF_ROUTE_FIXES = 12

        val ROUTE =
            Route(
                geometry = listOf(SOUTH, MIDDLE, END),
                distanceMeters = PolylineLocator(listOf(SOUTH, MIDDLE, END)).totalMeters(),
                durationSeconds = 300.0,
            )

        fun fix(
            index: Int,
            point: GeoPoint,
        ): LocationFix = LocationFix(position = point, accuracyMeters = 5.0, timeMillis = index * 1_000L)
    }
}
