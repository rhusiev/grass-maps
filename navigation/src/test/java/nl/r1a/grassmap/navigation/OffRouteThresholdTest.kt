package nl.r1a.grassmap.navigation

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-0309's off-route threshold.
 *
 * One threshold decides in both places: Ferrostar's `StaticThreshold` detector
 * is built with [NavigationConfig.maxDeviationMeters] and the session's
 * geometry floor compares against the same value. When the engine returns a
 * verdict it is authoritative, because the engine also gates on the fix's
 * horizontal accuracy; the floor only answers when there is no verdict. Two
 * consecutive off-route fixes are needed, and a fix that is off the current
 * step but still on the route never counts.
 */
class OffRouteThresholdTest {
    @Test
    fun aFixUnderTheThresholdStaysOnRoute() =
        runTest {
            val state = follow(GeometryRouteFollower(), listOf(offsetFix(THRESHOLD_METERS - 5.0), offsetFix(THRESHOLD_METERS - 5.0)))

            assertEquals(RouteRelation.OnRoute, state.relation)
        }

    @Test
    fun aFixOverTheThresholdNeedsTwoFixesAndThenReadsOffRoute() =
        runTest {
            val first = follow(GeometryRouteFollower(), listOf(offsetFix(THRESHOLD_METERS + 5.0)))
            assertEquals(RouteRelation.OffStepOnRoute, first.relation)

            val state = follow(GeometryRouteFollower(), listOf(offsetFix(THRESHOLD_METERS + 5.0), offsetFix(THRESHOLD_METERS + 5.0)))
            assertEquals(RouteRelation.OffRoute, state.relation)
            assertEquals(THRESHOLD_METERS + 5.0, state.distanceFromRouteMeters, METRES_TOLERANCE)
        }

    @Test
    fun theEnginesVerdictWinsOverTheGeometryFloor() =
        runTest {
            // Ten metres from the line, but the core says the fix left the route.
            val follower =
                ScriptedFollower { fix ->
                    FollowerSnapshot(
                        snappedPosition = fix.position,
                        deviation = FollowerDeviation.OffRoute,
                        deviationMeters = 10.0,
                        remainingDistanceMeters = ROUTE_LENGTH - 10.0,
                    )
                }

            val state = follow(follower, listOf(offsetFix(10.0), offsetFix(10.0)))

            assertEquals(RouteRelation.OffRoute, state.relation)
        }

    @Test
    fun anOffStepReadingIsStillOnTheRoute() =
        runTest {
            val follower =
                ScriptedFollower { fix ->
                    FollowerSnapshot(
                        snappedPosition = fix.position,
                        deviation = FollowerDeviation.OffStepOnRoute,
                        deviationMeters = THRESHOLD_METERS * 3.0,
                        remainingDistanceMeters = ROUTE_LENGTH - 150.0,
                    )
                }

            val state = follow(follower, listOf(offsetFix(1.0), offsetFix(1.0)))

            assertEquals(RouteRelation.OnRoute, state.relation)
        }

    private suspend fun follow(
        follower: RouteFollowerEngine,
        fixes: List<LocationFix>,
    ): NavigationState {
        val session =
            NavigationSession(
                follower = follower,
                routing = NoRoutingEngine,
                profile = RouteProfile.Walk,
                config = NavigationConfig(reroute = false),
                clockMillis = { 0L },
            )
        session.run(ROUTE, flowOf(*fixes.toTypedArray())).valueOrFail()
        return session.state.value
    }

    private fun offsetFix(meters: Double): LocationFix =
        LocationFix(
            position = GeoMath.offset(LINE_START, EAST, meters),
            accuracyMeters = 5.0,
            timeMillis = BASE_MILLIS,
        )

    private companion object {
        const val EAST = 90.0
        const val THRESHOLD_METERS = 35.0
        const val METRES_TOLERANCE = 0.5
        const val BASE_MILLIS = 1_700_000_000_000L
        val LINE_START = GeoPoint(49.8400, 24.0300)
        val LINE_END = GeoPoint(49.8490, 24.0300)
        val GEOMETRY = listOf(LINE_START, LINE_END)
        val ROUTE_LENGTH = 1_000.0
        val ROUTE =
            Route(
                geometry = GEOMETRY,
                distanceMeters = ROUTE_LENGTH,
                durationSeconds = 720.0,
            )
    }
}
