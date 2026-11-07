package nl.r1a.grassmap.app

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.navigation.RouteFollowerEngine
import nl.r1a.grassmap.navigation.RouteFollowerHandle
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
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

class NavCommandsTest {
    @Test
    fun theReplayLeavesTheRouteReroutesAndArrives() =
        runTest {
            val host = FakeMapHost(CAMERA)
            val engine = FakeEngine()
            val result = command(host, engine).execute(JsonNull)

            val summary = (result as Outcome.Success<CommandResult<String>>).value.value
            assertTrue(summary.contains("reroutes=1"))
            assertTrue(summary.contains("arrived=true"))
            assertTrue(summary.contains("detection_ms="))
            assertTrue(summary.contains("off_route_fixes="))
            assertEquals(2, host.routes.size)
            assertEquals(2, engine.requests.size)
            assertEquals(DESTINATION, engine.requests[1].waypoints.last())
            assertEquals(RouteProfile.Walk, engine.requests[1].profile)
            assertTrue(engine.requests[1].waypoints.first() != engine.requests[0].waypoints.first())
        }

    @Test
    fun rerouteCanBeTurnedOff() =
        runTest {
            val host = FakeMapHost(CAMERA)
            val engine = FakeEngine()

            val result =
                command(host, engine).execute(
                    JsonObject(mapOf("reroute" to JsonPrimitive(false))),
                )

            val summary = (result as Outcome.Success<CommandResult<String>>).value.value
            assertTrue(summary.contains("reroutes=0"))
            assertTrue(summary.contains("arrived=true"))
            assertEquals(1, engine.requests.size)
        }

    @Test
    fun anUnknownAreaIsInvalidInput() =
        runTest {
            val host = FakeMapHost(CAMERA)

            val result =
                command(host, FakeEngine()).execute(
                    JsonObject(mapOf("area" to JsonPrimitive("nowhere"))),
                )

            assertEquals(Outcome.Failure(AppError.InvalidInput), result)
            assertTrue(host.routes.isEmpty())
        }

    @Test
    fun aMissingMapHostIsNotReady() =
        runTest {
            val result =
                TestFollowCommand(
                    session = MapSessionHolder(),
                    engine = { FakeEngine() },
                    log = {},
                    tickMillis = TEST_TICK_MILLIS,
                    delayMillis = {},
                ).execute(JsonNull)

            assertEquals(Outcome.Failure(AppError.NotReady), result)
        }

    @Test
    fun aMissingRoutingEngineIsNotReady() =
        runTest {
            val session = MapSessionHolder()
            session.bind(FakeMapHost(CAMERA))

            val result =
                TestFollowCommand(
                    session = session,
                    engine = { null },
                    log = {},
                    tickMillis = TEST_TICK_MILLIS,
                    delayMillis = {},
                ).execute(JsonNull)

            assertEquals(Outcome.Failure(AppError.NotReady), result)
        }

    private fun command(
        host: FakeMapHost,
        engine: RoutingEngine,
    ): TestFollowCommand {
        val session = MapSessionHolder()
        session.bind(host)
        return TestFollowCommand(
            session = session,
            engine = { engine },
            log = {},
            followerFactory = { UnavailableFollower },
            tickMillis = TEST_TICK_MILLIS,
            delayMillis = {},
        )
    }

    /** Keeps the session on the geometry fallback and off the native library in JVM tests. */
    private object UnavailableFollower : RouteFollowerEngine {
        override fun begin(route: Route): Outcome<RouteFollowerHandle> = Outcome.Failure(AppError.Unsupported)
    }

    private class FakeEngine : RoutingEngine {
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
            return if (requests.size == 1) {
                Outcome.Success(listOf(BASE))
            } else {
                Outcome.Success(listOf(replacementOf(request.waypoints.first())))
            }
        }
    }

    private companion object {
        const val TEST_TICK_MILLIS = 1L
        val CAMERA = Camera(center = GeoPoint(49.94, 23.94), zoom = 13.0)
        val DESTINATION = GeoPoint(49.94, 23.9423)

        /** A straight line with 12 vertices so the replay has on/detour/on thirds. */
        val BASE =
            Route(
                geometry = (0 until 12).map { index -> GeoPoint(49.94, 23.939 + index * 0.00030) },
                distanceMeters = 260.0,
                durationSeconds = 190.0,
                instructions =
                    listOf(
                        nl.r1a.grassmap.plugin.api.provider
                            .RouteInstruction("Walk south", null, 0.0, 60.0),
                        nl.r1a.grassmap.plugin.api.provider
                            .RouteInstruction("Arrive", null, 260.0, 0.0),
                    ),
            )

        fun replacementOf(from: GeoPoint): Route =
            Route(
                geometry = listOf(from, GeoPoint(49.94, from.longitude + 0.0005), DESTINATION),
                distanceMeters = 120.0,
                durationSeconds = 90.0,
            )
    }
}
