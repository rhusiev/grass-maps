package nl.r1a.grassmap.routing

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanRole
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.service.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Intermediate stops (T-0303) against the captured answers in
 * `test-data/routing/via-vectors.json`.
 *
 * The ten-metre rule is measured on the line Valhalla returned for each profile,
 * not on a line this test constructs. The same vectors prove that a reorder
 * produces a different line in the new order and that a removal shortens the
 * chain by one leg.
 */
class RoutePlannerViaTest {
    @Test
    fun everyProfilePassesWithinTenMetresOfEveryViaInOrder() =
        runTest {
            for (profile in PROFILES) {
                val case = ViaVectors.caseFor(profile, ALL_VIAS) ?: error("no $profile three-via case")
                val planner = plannerFor(case)

                planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(ViaVectors.start))
                ViaVectors.vias.forEach { via -> planner.addVia(RoutePlanPoint(via)) }
                val state = ready(planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(ViaVectors.destination)))

                val line = state.selected?.geometry ?: error("no $profile line")
                val distances = ViaVectors.vias.map { via -> RouteGeometry.distanceToLine(via, line) }
                distances.forEachIndexed { index, metres ->
                    assertTrue(
                        "$profile via $index (${ViaVectors.viasByName[index]}) is $metres m from the line",
                        metres <= TEN_METRES,
                    )
                }
                val order = ViaVectors.vias.map { via -> RouteGeometry.nearestIndex(via, line) }
                assertTrue("$profile reaches the vias out of order: $order", order == order.sorted())
                assertEquals(case.route.distanceMeters, state.selected?.distanceMeters ?: -1.0, 0.0)
            }
        }

    @Test
    fun theThreeViasAreOneLegChainAndOneRouteRequest() =
        runTest {
            val case = ViaVectors.caseFor(RouteProfile.Walk, ALL_VIAS) ?: error("no three-via case")
            val engine = ViaEngine(case.profile)
            val planner = RoutePlanner(engine::route, clock = CLOCK, profile = case.profile)

            planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(ViaVectors.start))
            ViaVectors.vias.forEach { via -> planner.addVia(RoutePlanPoint(via)) }
            planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(ViaVectors.destination))

            assertEquals(1, engine.requests.size)
            assertEquals(case.waypoints, engine.requests.single().waypoints)
            assertEquals(
                THREE_VIAS_AND_TWO_ENDS,
                engine.requests
                    .single()
                    .waypoints.size,
            )
            assertEquals(THREE_VIAS_AND_TWO_ENDS - 1, case.route.segments.size)
        }

    @Test
    fun reorderingViasChangesTheLineAndTheNewOrderIsRespected() =
        runTest {
            val forward = ViaVectors.caseFor(RouteProfile.Bike, ALL_VIAS) ?: error("no forward case")
            val reordered = ViaVectors.caseFor(RouteProfile.Bike, REORDERED_VIAS) ?: error("no reordered case")
            val engine = ViaEngine(RouteProfile.Bike)
            val planner = RoutePlanner(engine::route, clock = CLOCK, profile = RouteProfile.Bike)
            planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(ViaVectors.start))
            ViaVectors.vias.forEach { via -> planner.addVia(RoutePlanPoint(via)) }
            val before = ready(planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(ViaVectors.destination)))

            val moved = ready(planner.moveVia(1, 2))

            assertEquals(reordered.waypoints, engine.requests.last().waypoints)
            assertEquals(listOf(0, 2, 1), moved.vias.map { via -> ViaVectors.vias.indexOf(via.position) })
            val line = moved.selected?.geometry ?: error("no reordered line")
            assertEquals(reordered.route.geometry, line)
            assertNotEquals(before.selected?.geometry, moved.selected?.geometry)
            assertTrue(
                "reordering did not change the distance",
                (moved.selected?.distanceMeters ?: 0.0) > (before.selected?.distanceMeters ?: 0.0),
            )
            val order = ViaVectors.vias.let { vias -> listOf(0, 2, 1).map { index -> RouteGeometry.nearestIndex(vias[index], line) } }
            assertTrue("the reordered line does not visit the stops in the new order: $order", order == order.sorted())
            listOf(0, 2, 1).forEach { index ->
                val metres = RouteGeometry.distanceToLine(ViaVectors.vias[index], line)
                assertTrue("via $index is $metres m from the reordered line", metres <= TEN_METRES)
            }
        }

    @Test
    fun removingAViaDropsItsLegAndTheLineChanges() =
        runTest {
            val threeVias = ViaVectors.caseFor(RouteProfile.Bike, ALL_VIAS) ?: error("no three-via case")
            val removal = ViaVectors.caseFor(RouteProfile.Bike, ALL_VIAS.dropLast(1)) ?: error("no removal case")
            val engine = ViaEngine(RouteProfile.Bike)
            val planner = RoutePlanner(engine::route, clock = CLOCK, profile = RouteProfile.Bike)
            planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(ViaVectors.start))
            ViaVectors.vias.forEach { via -> planner.addVia(RoutePlanPoint(via)) }
            val before = ready(planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(ViaVectors.destination)))

            val removed = ready(planner.removeVia(2))

            assertEquals(removal.waypoints, engine.requests.last().waypoints)
            assertEquals(2, removed.vias.size)
            assertEquals(removal.route.geometry, removed.selected?.geometry)
            assertNotEquals(before.selected?.geometry, removed.selected?.geometry)
            assertEquals(threeVias.route.segments.size - 1, removal.route.segments.size)
            assertNotEquals(before.selected?.distanceMeters, removed.selected?.distanceMeters)
        }

    @Test
    fun removingTheFirstViaLeavesTheOtherStopsInOrder() =
        runTest {
            val engine = ViaEngine(RouteProfile.Bike)
            val planner = RoutePlanner(engine::route, clock = CLOCK, profile = RouteProfile.Bike)
            planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(ViaVectors.start))
            ViaVectors.vias.forEach { via -> planner.addVia(RoutePlanPoint(via)) }
            ready(planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(ViaVectors.destination)))

            planner.removeVia(0)

            assertEquals(
                listOf(ViaVectors.vias[1], ViaVectors.vias[2]),
                planner.state.value.vias
                    .map { via -> via.position },
            )
            assertEquals(
                listOf(ViaVectors.start, ViaVectors.vias[1], ViaVectors.vias[2], ViaVectors.destination),
                engine.requests.last().waypoints,
            )
        }

    @Test
    fun aViaAddedWhileTheDestinationIsUnsetKeepsPickingAndIsRoutedWithIt() =
        runTest {
            val case = ViaVectors.caseFor(RouteProfile.Walk, ALL_VIAS) ?: error("no three-via case")
            val engine = ViaEngine(RouteProfile.Walk)
            val planner = RoutePlanner(engine::route, clock = CLOCK, profile = RouteProfile.Walk)
            planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(ViaVectors.start))

            ViaVectors.vias.forEach { via -> planner.addVia(RoutePlanPoint(via)) }

            assertEquals(RoutePlanStatus.PickingDestination, planner.state.value.status)
            assertTrue(engine.requests.isEmpty())
            assertEquals(3, planner.state.value.vias.size)

            val state = ready(planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(ViaVectors.destination)))

            assertEquals(case.waypoints, engine.requests.single().waypoints)
            assertEquals(case.route.distanceMeters, state.selected?.distanceMeters ?: -1.0, 0.0)
        }

    @Test
    fun swapReversesTheViaOrderInsteadOfOnlySwappingTheEnds() =
        runTest {
            val engine = ViaEngine(RouteProfile.Bike)
            val planner = RoutePlanner(engine::route, clock = CLOCK, profile = RouteProfile.Bike)
            planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(ViaVectors.start))
            ViaVectors.vias.forEach { via -> planner.addVia(RoutePlanPoint(via)) }
            ready(planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(ViaVectors.destination)))

            planner.swap()

            assertEquals(
                listOf(ViaVectors.destination) + ViaVectors.vias.reversed() + ViaVectors.start,
                engine.requests.last().waypoints,
            )
            assertEquals(
                ViaVectors.vias.reversed(),
                planner.state.value.vias
                    .map { via -> via.position },
            )
        }

    @Test
    fun anOutOfRangeViaIsInvalidInputAndLeavesThePlanAlone() =
        runTest {
            val case = ViaVectors.caseFor(RouteProfile.Walk, ALL_VIAS) ?: error("no three-via case")
            val engine = ViaEngine(RouteProfile.Walk)
            val planner = RoutePlanner(engine::route, clock = CLOCK, profile = RouteProfile.Walk)
            planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(ViaVectors.start))
            ViaVectors.vias.forEach { via -> planner.addVia(RoutePlanPoint(via)) }
            val ready = ready(planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(ViaVectors.destination)))

            assertEquals(Outcome.Failure(AppError.InvalidInput), planner.removeVia(3))
            assertEquals(Outcome.Failure(AppError.InvalidInput), planner.removeVia(-1))
            assertEquals(Outcome.Failure(AppError.InvalidInput), planner.moveVia(0, 3))
            assertEquals(ready, planner.state.value)
        }

    @Test
    fun movingAViaOntoItselfKeepsTheRouteAndAsksTheEngineAgainOnlyOnAChange() =
        runTest {
            val case = ViaVectors.caseFor(RouteProfile.Walk, ALL_VIAS) ?: error("no three-via case")
            val engine = ViaEngine(RouteProfile.Walk)
            val planner = RoutePlanner(engine::route, clock = CLOCK, profile = RouteProfile.Walk)
            planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(ViaVectors.start))
            ViaVectors.vias.forEach { via -> planner.addVia(RoutePlanPoint(via)) }
            ready(planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(ViaVectors.destination)))
            val requestsBefore = engine.requests.size

            val state = ready(planner.moveVia(1, 1))

            assertEquals(requestsBefore, engine.requests.size)
            assertEquals(case.waypoints, engine.requests.last().waypoints)
            assertEquals(RoutePlanStatus.Ready, state.status)
        }

    @Test
    fun aViaChainGetsTheEnginesSingleAnswerAsOneOption() =
        runTest {
            val case = ViaVectors.caseFor(RouteProfile.Bike, ALL_VIAS) ?: error("no three-via case")
            val engine = ViaEngine(RouteProfile.Bike)
            val planner = RoutePlanner(engine::route, clock = CLOCK, profile = RouteProfile.Bike, alternatives = 1)
            planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(ViaVectors.start))
            ViaVectors.vias.forEach { via -> planner.addVia(RoutePlanPoint(via)) }

            val state = ready(planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(ViaVectors.destination)))

            assertEquals(1, engine.requests.single().alternatives)
            assertEquals(1, state.options.size)
            assertEquals(0, state.selectedIndex)
        }

    @Test
    fun theStopsAreStartViasDestinationInThatOrder() =
        runTest {
            val planner = RoutePlanner(ViaEngine(RouteProfile.Walk)::route, clock = CLOCK, profile = RouteProfile.Walk)
            planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(ViaVectors.start))
            ViaVectors.vias.forEach { via -> planner.addVia(RoutePlanPoint(via)) }

            val state = planner.state.value

            assertEquals(
                listOf(ViaVectors.start) + ViaVectors.vias,
                state.stops.map { stop -> stop.position },
            )
        }

    private class ViaEngine(
        private val profile: RouteProfile,
    ) {
        val requests = mutableListOf<RouteRequest>()

        suspend fun route(
            request: RouteRequest,
            cancellation: CancellationHandle,
        ): Outcome<List<Route>> {
            requests += request
            val case =
                ViaVectors.cases.firstOrNull { case ->
                    case.profile == profile && case.waypoints == request.waypoints
                } ?: return Outcome.Failure(AppError.NotFound)
            return Outcome.Success(listOf(case.route))
        }
    }

    private companion object {
        const val TEN_METRES = 10.0
        const val THREE_VIAS_AND_TWO_ENDS = 5

        val PROFILES = listOf(RouteProfile.Walk, RouteProfile.Bike, RouteProfile.Hike)
        val ALL_VIAS = listOf(0, 1, 2)
        val REORDERED_VIAS = listOf(0, 2, 1)
        val CLOCK = Clock { 1_700_000_000_000L }

        fun plannerFor(case: ViaCase): RoutePlanner = RoutePlanner(ViaEngine(case.profile)::route, clock = CLOCK, profile = case.profile)

        fun ready(outcome: Outcome<RoutePlanState>): RoutePlanState {
            val state = (outcome as? Outcome.Success)?.value ?: error("expected a planned state, got $outcome")
            check(state.status == RoutePlanStatus.Ready) { "expected Ready, got ${state.status}" }
            return state
        }
    }
}
