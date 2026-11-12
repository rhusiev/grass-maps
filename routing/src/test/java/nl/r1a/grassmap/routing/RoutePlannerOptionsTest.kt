package nl.r1a.grassmap.routing

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanRole
import nl.r1a.grassmap.plugin.api.provider.Hilliness
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.service.Clock
import org.junit.Assert.assertEquals
import org.junit.Test

/** Per-profile routing options on the planner request (T-0308). */
class RoutePlannerOptionsTest {
    @Test
    fun aBikeChangeLeavesTheWalkRequestIdentical() =
        runTest {
            val engine = CapturingEngine()
            var bike = RouteOptions()
            val planner =
                RoutePlanner(
                    requestRoute = engine::route,
                    clock = Clock { NOW_MILLIS },
                    profile = RouteProfile.Walk,
                    optionsForProfile = { profile -> if (profile == RouteProfile.Bike) bike else RouteOptions() },
                )
            planner.setPoint(RoutePlanRole.Start, START)
            planner.setPoint(RoutePlanRole.Destination, DESTINATION)
            val walkBefore = engine.requests.single()

            bike = RouteOptions(Hilliness.AvoidHills)
            planner.plan()
            val walkAfter = engine.requests.last()

            assertEquals("a bike setting must not reach the walk request", walkBefore, walkAfter)
            assertEquals(RouteOptions(), walkAfter.options)
        }

    @Test
    fun theBikePlannerCarriesItsOwnOptions() =
        runTest {
            val engine = CapturingEngine()
            val planner =
                RoutePlanner(
                    requestRoute = engine::route,
                    clock = Clock { NOW_MILLIS },
                    profile = RouteProfile.Bike,
                    optionsForProfile = { profile ->
                        if (profile == RouteProfile.Bike) {
                            RouteOptions(Hilliness.AvoidHills)
                        } else {
                            RouteOptions()
                        }
                    },
                )

            planner.setPoint(RoutePlanRole.Start, START)
            planner.setPoint(RoutePlanRole.Destination, DESTINATION)

            assertEquals(RouteOptions(Hilliness.AvoidHills), engine.requests.single().options)
            assertEquals(RouteProfile.Bike, engine.requests.single().profile)
        }

    private class CapturingEngine {
        val requests = mutableListOf<RouteRequest>()

        suspend fun route(
            request: RouteRequest,
            cancellation: CancellationHandle,
        ): Outcome<List<Route>> {
            requests += request
            return Outcome.Success(
                listOf(
                    Route(
                        geometry = request.waypoints,
                        distanceMeters = 1_000.0,
                        durationSeconds = 600.0,
                    ),
                ),
            )
        }
    }

    private companion object {
        const val NOW_MILLIS = 1_700_000_000_000L
        val START = RoutePlanPoint(GeoPoint(49.8290, 23.9520), "Start")
        val DESTINATION = RoutePlanPoint(GeoPoint(49.8420, 24.0320), "Destination")
    }
}
