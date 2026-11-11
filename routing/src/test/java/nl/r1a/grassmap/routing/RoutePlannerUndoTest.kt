package nl.r1a.grassmap.routing

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.RouteHistoryAccess
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RouteHistoryEntry
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanRole
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import nl.r1a.grassmap.plugin.api.service.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner's edit history (T-0304).
 *
 * Every assertion fails without the undo stack: the edit test compares the five
 * published states to the states five undos and five redos restore, the no-route
 * test counts engine requests, and the clear test proves the history is gone.
 */
class RoutePlannerUndoTest {
    private val engine = RecordingEngine()

    @Test
    fun fiveEditsUndoAndRedoToTheSameFiveStates() =
        runTest {
            val planner = planner()
            planner.begin()
            val before = planner.state.value
            val afterEditing = mutableListOf<RoutePlanState>()

            afterEditing += edit(planner) { planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(START, "start")) }
            afterEditing += edit(planner) { planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(DESTINATION, "destination")) }
            afterEditing += edit(planner) { planner.addVia(RoutePlanPoint(VIA_ONE, "Cafe")) }
            afterEditing += edit(planner) { planner.addVia(RoutePlanPoint(VIA_TWO, "Museum")) }
            afterEditing += edit(planner) { planner.moveVia(0, 1) }

            val undone = mutableListOf<RoutePlanState>()
            repeat(EDITS) { undone += edit(planner) { planner.undo() } }
            assertEquals(
                "undo must walk back through every edit",
                (listOf(before) + afterEditing.dropLast(1)).map { state -> state.withoutReach() },
                undone.reversed().map { state -> state.withoutReach() },
            )

            val redone = mutableListOf<RoutePlanState>()
            repeat(EDITS) { redone += edit(planner) { planner.redo() } }

            assertEquals(
                "redo must return to the same five states",
                afterEditing.map { state -> state.withoutReach() },
                redone.map { state -> state.withoutReach() },
            )
            assertEquals(afterEditing.last(), planner.state.value)
        }

    @Test
    fun undoTakesTheRouteWithTheEditAndAsksNoEngineAgain() =
        runTest {
            val planner = planner()
            planner.begin()
            planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(START))
            val routed = edit(planner) { planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(DESTINATION)) }
            val line = routed.selected?.geometry ?: error("no route")
            edit(planner) { planner.addVia(RoutePlanPoint(VIA_ONE)) }
            val requests = engine.requests.size

            val restored = edit(planner) { planner.undo() }

            assertEquals("the route request is part of the edit", requests, engine.requests.size)
            assertEquals(RoutePlanStatus.Ready, restored.status)
            assertEquals(routed.withoutReach(), restored.withoutReach())
            assertEquals(line, restored.selected?.geometry)
            assertTrue("undo must not leave a dead redo", restored.redoAvailable)
        }

    @Test
    fun undoAndRedoReportTheirReachInThePublishedState() =
        runTest {
            val planner = planner()
            planner.begin()
            assertFalse(planner.state.value.undoAvailable)
            assertFalse(planner.state.value.redoAvailable)

            edit(planner) { planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(START)) }
            assertTrue(planner.state.value.undoAvailable)
            assertFalse(planner.state.value.redoAvailable)

            edit(planner) { planner.undo() }
            assertFalse(planner.state.value.undoAvailable)
            assertTrue(planner.state.value.redoAvailable)

            edit(planner) { planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(VIA_ONE)) }
            assertTrue(planner.state.value.undoAvailable)
            assertFalse("a new edit drops the redo branch", planner.state.value.redoAvailable)
        }

    @Test
    fun undoWithoutAnEditIsInvalidAndChangesNothing() =
        runTest {
            val planner = planner()
            planner.begin()
            val before = planner.state.value

            assertEquals(Outcome.Failure(AppError.InvalidInput), planner.undo())
            assertEquals(Outcome.Failure(AppError.InvalidInput), planner.redo())
            assertEquals(before, planner.state.value)
        }

    @Test
    fun clearDropsTheEditHistory() =
        runTest {
            val planner = planner()
            planner.begin()
            edit(planner) { planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(START)) }

            planner.clear()

            assertFalse(planner.state.value.undoAvailable)
            assertFalse(planner.state.value.redoAvailable)
            assertEquals(Outcome.Failure(AppError.InvalidInput), planner.undo())
        }

    @Test
    fun aProducedRouteIsRememberedAndUndoDoesNotRememberAgain() =
        runTest {
            val history = FakeHistory()
            val planner = planner(history)
            planner.begin()
            planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(START))
            edit(planner) { planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(DESTINATION)) }

            assertEquals(1, history.remembered.size)
            assertEquals(
                listOf(START, DESTINATION),
                history.remembered
                    .single()
                    .stops
                    .map { point -> point.position },
            )

            edit(planner) { planner.undo() }

            assertEquals("an undo is not a use", 1, history.remembered.size)
            assertEquals(1, planner.recent.value.size)
        }

    @Test
    fun reusingAStoredRouteSetsTheWholeChainAsOneEdit() =
        runTest {
            val planner = planner()
            planner.begin()
            val entry =
                RouteHistoryEntry(
                    start = RoutePlanPoint(START, "start"),
                    destination = RoutePlanPoint(DESTINATION, "destination"),
                    vias = listOf(RoutePlanPoint(VIA_ONE, "Cafe")),
                )

            val state = edit(planner) { planner.reuse(entry) }

            assertEquals(RoutePlanStatus.Ready, state.status)
            assertEquals(listOf(START, VIA_ONE, DESTINATION), state.stops.map { point -> point.position })
            assertEquals(1, engine.requests.size)
            assertEquals(listOf(START, VIA_ONE, DESTINATION), engine.requests.single().waypoints)

            val undone = edit(planner) { planner.undo() }
            assertEquals(RoutePlanStatus.PickingStart, undone.status)
            assertTrue(undone.stops.isEmpty())
        }

    private fun planner(history: RouteHistoryAccess = NoHistory) =
        RoutePlanner(
            requestRoute = engine::route,
            clock = Clock { NOW_MILLIS },
            profile = RouteProfile.Walk,
            history = history,
        )

    private suspend fun edit(
        planner: RoutePlanner,
        action: suspend () -> Outcome<RoutePlanState>,
    ): RoutePlanState {
        val state = (action() as? Outcome.Success)?.value ?: error("edit failed")
        return state
    }

    /**
     * The plan without the two control flags. A restored state necessarily has
     * a different reach (it can be redone), so the comparison of the planning
     * state itself leaves the buttons out; the reach is asserted separately.
     */
    private fun RoutePlanState.withoutReach(): RoutePlanState = copy(undoAvailable = false, redoAvailable = false)

    private class RecordingEngine : RoutingEngine {
        val requests = mutableListOf<RouteRequest>()

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
            requests += request
            return Outcome.Success(listOf(routeFor(request.waypoints)))
        }
    }

    private class FakeHistory : RouteHistoryAccess {
        val remembered = mutableListOf<RouteHistoryEntry>()

        override suspend fun remember(entry: RouteHistoryEntry): Outcome<List<RouteHistoryEntry>> {
            remembered += entry
            return Outcome.Success(remembered.reversed())
        }

        override suspend fun recent(): Outcome<List<RouteHistoryEntry>> = Outcome.Success(remembered.reversed())

        override suspend fun clear(): Outcome<Unit> {
            remembered.clear()
            return Outcome.Success(Unit)
        }
    }

    private object NoHistory : RouteHistoryAccess {
        override suspend fun remember(entry: RouteHistoryEntry): Outcome<List<RouteHistoryEntry>> = Outcome.Success(emptyList())

        override suspend fun recent(): Outcome<List<RouteHistoryEntry>> = Outcome.Success(emptyList())

        override suspend fun clear(): Outcome<Unit> = Outcome.Success(Unit)
    }

    private companion object {
        const val EDITS = 5
        const val NOW_MILLIS = 1_700_000_000_000L
        val START = GeoPoint(49.8290, 23.9520)
        val DESTINATION = GeoPoint(49.8420, 24.0320)
        val VIA_ONE = GeoPoint(49.8330, 23.9700)
        val VIA_TWO = GeoPoint(49.8370, 23.9900)

        fun routeFor(waypoints: List<GeoPoint>): Route =
            Route(
                geometry = listOf(waypoints.first(), waypoints.last()),
                distanceMeters = 1_000.0 * waypoints.size,
                durationSeconds = 60.0 * waypoints.size,
            )
    }
}
