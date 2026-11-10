package nl.r1a.grassmap.routing

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanRole
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.service.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planning state machine. The routes are a captured Valhalla answer
 * (`test-data/routing/planner-vectors.json`), so the swap case has a genuinely
 * different reverse line: on the bicycle profile the reverse route is 6 582 m
 * against 7 052 m forward, not the forward line read backwards.
 */
class RoutePlannerTest {
    @Test
    fun twoPointsBecomeTheEnginesOptionsAndAnArrival() =
        runTest {
            val engine = FakeEngine()
            val planner = RoutePlanner(engine::route, clock = clock(), profile = RouteProfile.Bike)

            planner.setPoint(RoutePlanRole.Start, START)
            val result = planner.setPoint(RoutePlanRole.Destination, DESTINATION)

            val state = (result as Outcome.Success).value
            assertEquals(RoutePlanStatus.Ready, state.status)
            assertEquals(listOf(PRIMARY_DISTANCE, ALTERNATIVE_DISTANCE), state.options.map { it.distanceMeters })
            assertEquals(0, state.selectedIndex)
            assertEquals(1L, state.generation)
            assertEquals(engine.requests.single().alternatives, 1)
            assertEquals(
                listOf(START.position, DESTINATION.position),
                engine.requests.single().waypoints,
            )
            val primary = state.options.first()
            assertEquals(NOW_MILLIS + PRIMARY_DURATION.toLong() * 1_000L, primary.arrivalEpochMillis)
        }

    @Test
    fun swapRoutesTheReversedPairAndKeepsTheEnginesOwnLine() =
        runTest {
            val engine = FakeEngine()
            val planner = RoutePlanner(engine::route, clock = clock(), profile = RouteProfile.Bike)
            planner.setPoint(RoutePlanRole.Start, START)
            planner.setPoint(RoutePlanRole.Destination, DESTINATION)
            val forward = (planner.state.value).selected ?: error("no forward route")

            val swapped = planner.swap()

            assertEquals(2, engine.requests.size)
            assertEquals(
                listOf(DESTINATION.position, START.position),
                engine.requests[1].waypoints,
            )
            val state = (swapped as Outcome.Success).value
            val reverse = state.selected ?: error("no reverse route")
            assertEquals(START, state.destination)
            assertEquals(DESTINATION, state.start)
            assertEquals(engine.routes(RouteDirection.Reverse).primary.geometry, reverse.geometry)
            assertNotEquals(forward.geometry, reverse.geometry)
            assertNotEquals(forward.geometry, reverse.geometry.reversed())
            assertNotEquals(forward.distanceMeters, reverse.distanceMeters)
            assertEquals(2L, state.generation)
        }

    @Test
    fun selectingAnAlternativeChangesTheSelectedLine() =
        runTest {
            val planner = RoutePlanner(FakeEngine()::route, clock = clock(), profile = RouteProfile.Bike)
            planner.setPoint(RoutePlanRole.Start, START)
            val ready = (planner.setPoint(RoutePlanRole.Destination, DESTINATION) as Outcome.Success).value
            val primary = ready.selected ?: error("no primary route")

            val selected = (planner.select(1) as Outcome.Success).value

            assertEquals(1, selected.selectedIndex)
            assertEquals(2, selected.options.size)
            val alternative = selected.selected ?: error("no alternative")
            assertEquals(ALTERNATIVE_DISTANCE, alternative.distanceMeters, 0.0)
            assertNotEquals(primary.geometry, alternative.geometry)
            assertEquals(ready.generation, selected.generation)
        }

    @Test
    fun aPackWithoutAlternativesYieldsOneOptionAndNoSecondLine() =
        runTest {
            val engine = FakeEngine(alternativesOnly = false)
            val planner = RoutePlanner(engine::route, clock = clock(), profile = RouteProfile.Bike)

            planner.setPoint(RoutePlanRole.Start, START)
            val state = (planner.setPoint(RoutePlanRole.Destination, DESTINATION) as Outcome.Success).value

            assertEquals(RoutePlanStatus.Ready, state.status)
            assertEquals(1, state.options.size)
            assertEquals(1, engine.requests.single().alternatives)
            assertFalse(state.options.any { it.geometry == engine.routes(RouteDirection.Forward).alternative.geometry })
        }

    @Test
    fun aRoutedFailureIsAStateNotAnException() =
        runTest {
            val planner =
                RoutePlanner(
                    { _, _ -> Outcome.Failure(AppError.NotFound) },
                    clock = clock(),
                    profile = RouteProfile.Bike,
                )

            planner.setPoint(RoutePlanRole.Start, START)
            val result = planner.setPoint(RoutePlanRole.Destination, DESTINATION)

            assertEquals(Outcome.Failure(AppError.NotFound), result)
            assertEquals(RoutePlanStatus.Failed, planner.state.value.status)
            assertTrue(
                planner.state.value.options
                    .isEmpty(),
            )
        }

    @Test
    fun selectingOutsideTheReturnedListIsInvalidInput() =
        runTest {
            val planner = RoutePlanner(FakeEngine()::route, clock = clock(), profile = RouteProfile.Bike)
            planner.setPoint(RoutePlanRole.Start, START)
            planner.setPoint(RoutePlanRole.Destination, DESTINATION)

            assertEquals(Outcome.Failure(AppError.InvalidInput), planner.select(2))
            assertEquals(Outcome.Failure(AppError.InvalidInput), planner.select(-1))
        }

    @Test
    fun clearClosesThePlanAndDropsTheOptions() =
        runTest {
            val planner = RoutePlanner(FakeEngine()::route, clock = clock(), profile = RouteProfile.Bike)
            planner.setPoint(RoutePlanRole.Start, START)
            planner.setPoint(RoutePlanRole.Destination, DESTINATION)

            planner.clear()

            assertEquals(RoutePlanStatus.Idle, planner.state.value.status)
            assertTrue(
                planner.state.value.options
                    .isEmpty(),
            )
        }

    @Test
    fun theSelectedRouteIsTheOneAssemblyHandsToTheMap() =
        runTest {
            val planner = RoutePlanner(FakeEngine()::route, clock = clock(), profile = RouteProfile.Bike)
            planner.setPoint(RoutePlanRole.Start, START)
            planner.setPoint(RoutePlanRole.Destination, DESTINATION)
            val primary = planner.state.value.selectedRoute() ?: error("no primary route")

            planner.select(1)
            val alternative = planner.state.value.selectedRoute() ?: error("no alternative route")

            assertEquals(PRIMARY_DISTANCE, primary.distanceMeters, 0.0)
            assertEquals(ALTERNATIVE_DISTANCE, alternative.distanceMeters, 0.0)
            assertNotEquals(primary.geometry, alternative.geometry)
        }

    private class FakeEngine(
        private val alternativesOnly: Boolean = true,
    ) {
        val requests = mutableListOf<RouteRequest>()
        private val vectors = loadVectors()

        fun routes(direction: RouteDirection): VectorRoutes =
            when (direction) {
                RouteDirection.Forward -> vectors.forward
                RouteDirection.Reverse -> vectors.reverse
            }

        suspend fun route(
            request: RouteRequest,
            cancellation: CancellationHandle,
        ): Outcome<List<Route>> {
            requests += request
            val forward = request.waypoints.first() == START.position
            val routes = if (forward) vectors.forward else vectors.reverse
            val list =
                if (alternativesOnly) {
                    listOf(routes.primary, routes.alternative)
                } else {
                    listOf(routes.primary)
                }
            return Outcome.Success(list)
        }
    }

    private enum class RouteDirection { Forward, Reverse }

    private data class VectorRoutes(
        val primary: Route,
        val alternative: Route,
    )

    private data class Vectors(
        val forward: VectorRoutes,
        val reverse: VectorRoutes,
    )

    private companion object {
        const val NOW_MILLIS = 1_700_000_000_000L
        const val PRIMARY_DISTANCE = 7_052.0
        const val ALTERNATIVE_DISTANCE = 8_131.0
        const val PRIMARY_DURATION = 1_482.0

        val START = RoutePlanPoint(GeoPoint(49.8290, 23.9520), "Start")
        val DESTINATION = RoutePlanPoint(GeoPoint(49.8420, 24.0320), "Destination")

        fun clock(): Clock = Clock { NOW_MILLIS }

        fun loadVectors(): Vectors {
            val text =
                checkNotNull(
                    RoutePlannerTest::class.java.classLoader
                        ?.getResource("routing/planner-vectors.json"),
                ) { "missing routing/planner-vectors.json" }.readText()
            val root = Json.parseToJsonElement(text).jsonObject
            return Vectors(
                forward = root.getValue("forward").jsonArray.toVectorRoutes(),
                reverse = root.getValue("reverse").jsonArray.toVectorRoutes(),
            )
        }

        fun JsonArray.toVectorRoutes(): VectorRoutes {
            val routes = map { element -> element.jsonObject.toRoute() }
            return VectorRoutes(primary = routes[0], alternative = routes[1])
        }

        fun JsonObject.toRoute(): Route {
            val geometry =
                getValue("geometry").jsonArray.map { element ->
                    val point = element.jsonArray
                    GeoPoint(
                        latitude = (point[0] as JsonPrimitive).double,
                        longitude = (point[1] as JsonPrimitive).double,
                    )
                }
            return Route(
                geometry = geometry,
                distanceMeters = (getValue("distanceMeters") as JsonPrimitive).double,
                durationSeconds = (getValue("durationSeconds") as JsonPrimitive).double,
            )
        }
    }
}
