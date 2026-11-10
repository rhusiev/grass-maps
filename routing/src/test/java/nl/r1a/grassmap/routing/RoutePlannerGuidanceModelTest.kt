package nl.r1a.grassmap.routing

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanRole
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import nl.r1a.grassmap.plugin.api.service.Clock
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The planner keeps the engine's own guidance model (T-0309).
 *
 * The preview needs only totals, but guidance counts down per maneuver and per
 * leg, so the route's segments and instructions must survive the planner state
 * and come back out of [selectedRoute]. The model is also carried in the plugin
 * state, so it must round-trip through JSON unchanged.
 */
class RoutePlannerGuidanceModelTest {
    @Test
    fun thePlanKeepsTheEnginesLegsAndInstructions() =
        runTest {
            val planner =
                RoutePlanner(
                    requestRoute = { _, _ -> Outcome.Success(listOf(ROUTE)) },
                    clock = Clock { NOW_MILLIS },
                    profile = RouteProfile.Walk,
                )
            planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(START))
            val state = (planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(DESTINATION)) as Outcome.Success).value

            val option = state.options.single()
            assertEquals(ROUTE.segments, option.segments)
            assertEquals(ROUTE.instructions, option.instructions)

            val selected = requireNotNull(state.selectedRoute())
            assertEquals(ROUTE.geometry, selected.geometry)
            assertEquals(ROUTE.distanceMeters, selected.distanceMeters, 0.0)
            assertEquals(ROUTE.durationSeconds, selected.durationSeconds, 0.0)
            assertEquals(ROUTE.segments, selected.segments)
            assertEquals(ROUTE.instructions, selected.instructions)
        }

    @Test
    fun theModelRoundTripsThroughThePluginStateJson() =
        runTest {
            val planner =
                RoutePlanner(
                    requestRoute = { _, _ -> Outcome.Success(listOf(ROUTE)) },
                    clock = Clock { NOW_MILLIS },
                    profile = RouteProfile.Walk,
                )
            planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(START))
            val state = (planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(DESTINATION)) as Outcome.Success).value

            val encoded = Json.encodeToString(RoutePlanState.serializer(), state)
            val decoded = Json.decodeFromString(RoutePlanState.serializer(), encoded)

            assertEquals(state, decoded)
        }

    private companion object {
        const val NOW_MILLIS = 1_700_000_000_000L
        val START = GeoPoint(49.8400, 24.0300)
        val DESTINATION = GeoPoint(49.8490, 24.0300)
        val ROUTE =
            Route(
                geometry = listOf(START, DESTINATION),
                distanceMeters = 1_000.0,
                durationSeconds = 720.0,
                segments =
                    listOf(
                        RouteSegment(
                            startIndex = 0,
                            endIndex = 2,
                            distanceMeters = 1_000.0,
                            durationSeconds = 720.0,
                            attributes = mapOf("surface" to AttributeValue.TextValue("paved")),
                        ),
                    ),
                instructions =
                    listOf(
                        RouteInstruction("Walk north", "Franka", 0.0, 300.0, "1"),
                        RouteInstruction("Arrive", null, 600.0, 420.0, "4"),
                    ),
            )
    }
}
