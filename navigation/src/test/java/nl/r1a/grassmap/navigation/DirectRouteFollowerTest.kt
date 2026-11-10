package nl.r1a.grassmap.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
import nl.r1a.grassmap.plugins.direct.DirectRoutingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The direct engine's route inside the navigation seam (T-0305).
 *
 * Ferrostar's native core ships Android ABIs only, and the repository has no
 * instrumentation suite, so a desktop JVM test cannot run `FerrostarRouteFollower`.
 * The two halves below are the acceptance gate that does run here: the route
 * passes `FerrostarRouteMapper`, which is the follower's first stage and the
 * only place a route can be refused for its shape, and the session positions,
 * counts down and arrives on the same route through [RouteFollowerEngine].
 */
class DirectRouteFollowerTest {
    @Test
    fun theFollowerMapperAcceptsTheDirectRoute() =
        runTest {
            val route = directRoute()

            val ferrostar = FerrostarRouteMapper.toFerrostar(route)
            assertNotNull(ferrostar)
            val mapped = requireNotNull(ferrostar)
            val start = route.geometry.first()
            val end = route.geometry.last()
            val startWaypoint = mapped.waypoints.first().coordinate
            val endWaypoint = mapped.waypoints.last().coordinate
            val stepLine = mapped.steps.first().geometry

            assertEquals(route.geometry.size, mapped.geometry.size)
            assertEquals(route.distanceMeters, mapped.distance, METRES_TOLERANCE)
            assertEquals(start.latitude, startWaypoint.lat, COORDINATE_TOLERANCE)
            assertEquals(start.longitude, startWaypoint.lng, COORDINATE_TOLERANCE)
            assertEquals(end.latitude, endWaypoint.lat, COORDINATE_TOLERANCE)
            assertEquals(end.longitude, endWaypoint.lng, COORDINATE_TOLERANCE)
            assertEquals(start.latitude, stepLine.first().lat, COORDINATE_TOLERANCE)
            assertEquals(end.latitude, stepLine.last().lat, COORDINATE_TOLERANCE)
            assertEquals(route.distanceMeters, mapped.steps.sumOf { it.distance }, METRES_TOLERANCE)
            assertEquals(route.durationSeconds, mapped.steps.sumOf { it.duration }, SECONDS_TOLERANCE)
            assertTrue(mapped.steps.all { step -> step.geometry.size >= 2 })
        }

    @Test
    fun theFollowerSeesOnlyTheTwoEndsOfAViaRoute() =
        runTest {
            val route = directRoute(waypoints = listOf(START, VIA, END))

            val mapped = requireNotNull(FerrostarRouteMapper.toFerrostar(route))

            // The mapper builds a BREAK waypoint from each end of the geometry
            // and nothing else, so Ferrostar's waypoint advance cannot see the
            // via; the stop survives only in the line and in our segments. This
            // is a finding about the mapper, not something the direct route can
            // carry around it.
            assertEquals(2, mapped.waypoints.size)
            assertEquals(2, route.segments.size)
            assertEquals(1, mapped.steps.size)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theSessionPositionsTheDirectRouteAndCountsDown() =
        runTest {
            val route = directRoute()
            val locator = PolylineLocator(route.geometry)
            val fixes =
                listOf(
                    fixAt(requireNotNull(locator.pointAt(0.0))),
                    fixAt(requireNotNull(locator.pointAt(route.distanceMeters / 3.0))),
                    fixAt(route.geometry.last()),
                )
            val session = NavigationSession(LineFollower(), NoReroute, RouteProfile.Walk)
            val states = mutableListOf<NavigationState>()
            val collector = launch(UnconfinedTestDispatcher(testScheduler)) { session.state.collect { states += it } }

            val run = session.run(route, flowOf(*fixes.toTypedArray())).valueOrFail()
            collector.cancel()

            assertEquals(fixes.size, run.fixCount)
            assertTrue(run.arrived)

            val first = states.first { it.fix == fixes[0] }
            val firstSnap = requireNotNull(first.snappedPosition)
            assertEquals(fixes[0].position.latitude, firstSnap.latitude, COORDINATE_TOLERANCE)
            assertEquals(fixes[0].position.longitude, firstSnap.longitude, COORDINATE_TOLERANCE)
            assertEquals(route.distanceMeters, first.remainingDistanceMeters, METRES_TOLERANCE)
            assertEquals(route.durationSeconds, first.remainingDurationSeconds, SECONDS_TOLERANCE)
            assertEquals(RouteRelation.OnRoute, first.relation)

            val middle = states.first { it.fix == fixes[1] }
            val middleSnap = requireNotNull(middle.snappedPosition)
            assertEquals(NavigationPhase.Following, middle.phase)
            assertEquals(route.distanceMeters * TWO_THIRDS, middle.remainingDistanceMeters, METRES_TOLERANCE)
            assertEquals(route.durationSeconds * TWO_THIRDS, middle.remainingDurationSeconds, SECONDS_TOLERANCE)
            assertEquals(fixes[1].position.latitude, middleSnap.latitude, COORDINATE_TOLERANCE)
            assertEquals(
                route.durationSeconds * (middle.remainingDistanceMeters / route.distanceMeters),
                middle.remainingDurationSeconds,
                SECONDS_TOLERANCE,
            )
            assertTrue(middle.distanceFromRouteMeters < METRES_TOLERANCE)

            assertEquals(NavigationPhase.Arrived, session.state.value.phase)
            assertEquals(0.0, session.state.value.remainingDistanceMeters, METRES_TOLERANCE)
        }

    private suspend fun directRoute(waypoints: List<GeoPoint> = listOf(START, END)): Route {
        val request = RouteRequest(waypoints = waypoints, profile = RouteProfile.Walk)
        return when (val outcome = DirectRoutingEngine().route(request, CancellationHandle.None)) {
            is Outcome.Success -> outcome.value.single()
            is Outcome.Failure -> throw AssertionError("expected a route, got ${outcome.error}")
        }
    }

    private fun fixAt(position: GeoPoint): LocationFix =
        LocationFix(position = position, accuracyMeters = ACCURACY_METRES, timeMillis = 1_000L)

    private fun <T> Outcome<T>.valueOrFail(): T =
        when (this) {
            is Outcome.Success -> value
            is Outcome.Failure -> throw AssertionError("expected success, got $error")
        }

    /**
     * Stands in for the native follower, whose desktop ABI does not exist. It
     * reads the route the way the mapper hands it over - the line as geometry,
     * the declared distance and duration as the totals - so the session half
     * checks the route model, not the stand-in.
     */
    private class LineFollower : RouteFollowerEngine {
        override fun begin(route: Route): Outcome<RouteFollowerHandle> = Outcome.Success(Handle(route))

        private class Handle(
            private val route: Route,
        ) : RouteFollowerHandle {
            private val locator = PolylineLocator(route.geometry)

            override fun initialFix(fix: LocationFix): Outcome<FollowerSnapshot> = snapshotOf(fix)

            override fun update(fix: LocationFix): Outcome<FollowerSnapshot> = snapshotOf(fix)

            override fun close() = Unit

            private fun snapshotOf(fix: LocationFix): Outcome<FollowerSnapshot> {
                val projection = locator.locate(fix.position) ?: return Outcome.Failure(AppError.InvalidInput)
                val total = locator.totalMeters()
                val remainingFraction =
                    if (total > 0.0) {
                        (1.0 - projection.alongMeters / total).coerceIn(0.0, 1.0)
                    } else {
                        0.0
                    }
                return Outcome.Success(
                    FollowerSnapshot(
                        snappedPosition = projection.position,
                        remainingDistanceMeters = route.distanceMeters * remainingFraction,
                        remainingDurationSeconds = route.durationSeconds * remainingFraction,
                    ),
                )
            }
        }
    }

    /** The session reroutes only when a fix is off route, which this test never is. */
    private object NoReroute : RoutingEngine {
        override val contract: ProviderContract = RoutingEngineContract.REFERENCE

        override fun capabilities(): RoutingCapabilities = RoutingCapabilities(emptySet(), emptySet(), 0, MAX_ROUTE_METRES)

        override suspend fun route(
            request: RouteRequest,
            cancellation: CancellationHandle,
        ): Outcome<List<Route>> = Outcome.Failure(AppError.NotFound)
    }

    private companion object {
        const val METRES_TOLERANCE = 1.0
        const val SECONDS_TOLERANCE = 1.0
        const val COORDINATE_TOLERANCE = 1e-6
        const val ACCURACY_METRES = 3.0
        const val MAX_ROUTE_METRES = 250_000.0
        const val TWO_THIRDS = 2.0 / 3.0

        val START = GeoPoint(latitude = 0.0, longitude = 30.0)
        val END = GeoPoint(latitude = 0.0, longitude = 30.0081)
        val VIA = GeoPoint(latitude = 0.002, longitude = 30.004)
    }
}
