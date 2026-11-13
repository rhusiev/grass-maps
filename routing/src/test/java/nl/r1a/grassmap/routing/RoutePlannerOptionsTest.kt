package nl.r1a.grassmap.routing

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanRole
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.service.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Per-profile routing options on the planner request (T-0308, T-0421). */
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

            bike = RouteOptions(hills = RoutePreference.Avoid)
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
                            RouteOptions(hills = RoutePreference.Avoid)
                        } else {
                            RouteOptions()
                        }
                    },
                )

            planner.setPoint(RoutePlanRole.Start, START)
            planner.setPoint(RoutePlanRole.Destination, DESTINATION)

            assertEquals(RouteOptions(hills = RoutePreference.Avoid), engine.requests.single().options)
            assertEquals(RouteProfile.Bike, engine.requests.single().profile)
        }

    @Test
    fun aDisagreeingOverrideWinsForOneRouteAndTheProfileDefaultSurvives() =
        runTest {
            val profileDefaults =
                RouteOptions(
                    hills = RoutePreference.Avoid,
                    busyRoads = RoutePreference.DontMind,
                    roughSurfaces = RoutePreference.Normal,
                )
            val override =
                RouteOptions(
                    hills = RoutePreference.DontMind,
                    busyRoads = RoutePreference.Normal,
                    roughSurfaces = RoutePreference.Avoid,
                )
            val engine = CapturingEngine()
            val stored = profileDefaults
            val planner =
                RoutePlanner(
                    requestRoute = engine::route,
                    clock = Clock { NOW_MILLIS },
                    profile = RouteProfile.Walk,
                    optionsForProfile = { stored },
                )

            planner.setPoint(RoutePlanRole.Start, START)
            planner.setPoint(RoutePlanRole.Destination, DESTINATION)
            planner.setPreferences(override)

            assertEquals(override, engine.requests.last().options)
            val state = planner.state.value
            assertEquals(profileDefaults, state.profilePreferences)
            assertEquals(override, state.overridePreferences)
            assertEquals(override, state.preferences)
            assertEquals("the profile settings must not follow the route", profileDefaults, stored)

            planner.resetPreferences()

            assertEquals(profileDefaults, engine.requests.last().options)
            assertNull(planner.state.value.overridePreferences)
            assertEquals(profileDefaults, planner.state.value.preferences)
            assertEquals(profileDefaults, stored)
        }

    @Test
    fun theOverrideIsDroppedWhenThePlanCloses() =
        runTest {
            val engine = CapturingEngine()
            val profileDefaults = RouteOptions(busyRoads = RoutePreference.Avoid)
            val planner =
                RoutePlanner(
                    requestRoute = engine::route,
                    clock = Clock { NOW_MILLIS },
                    profile = RouteProfile.Walk,
                    optionsForProfile = { profileDefaults },
                )
            planner.setPoint(RoutePlanRole.Start, START)
            planner.setPoint(RoutePlanRole.Destination, DESTINATION)
            planner.setPreferences(RouteOptions(busyRoads = RoutePreference.DontMind))
            assertEquals(RouteOptions(busyRoads = RoutePreference.DontMind), engine.requests.last().options)

            planner.clear()
            planner.setPoint(RoutePlanRole.Start, START)
            planner.setPoint(RoutePlanRole.Destination, DESTINATION)

            assertEquals(profileDefaults, engine.requests.last().options)
            assertNull(planner.state.value.overridePreferences)
        }

    @Test
    fun applyingTheProfileDefaultsClearsTheOverride() =
        runTest {
            val engine = CapturingEngine()
            val profileDefaults = RouteOptions(roughSurfaces = RoutePreference.DontMind)
            val planner =
                RoutePlanner(
                    requestRoute = engine::route,
                    clock = Clock { NOW_MILLIS },
                    profile = RouteProfile.Walk,
                    optionsForProfile = { profileDefaults },
                )
            planner.setPoint(RoutePlanRole.Start, START)
            planner.setPoint(RoutePlanRole.Destination, DESTINATION)
            planner.setPreferences(RouteOptions(roughSurfaces = RoutePreference.Avoid))

            planner.setPreferences(profileDefaults)

            assertNull(planner.state.value.overridePreferences)
            assertEquals(profileDefaults, engine.requests.last().options)
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
