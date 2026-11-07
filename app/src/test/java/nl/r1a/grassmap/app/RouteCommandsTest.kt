package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteModifierKind
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCommandsTest {
    @Test
    fun aRouteIsDrawnAndReported() =
        runTest {
            val host = FakeMapHost(CAMERA)
            val engine = FakeEngine()

            val result = command(host, engine).execute(JsonNull)

            assertTrue(result is Outcome.Success)
            assertEquals(1, host.routes.size)
            assertEquals(BASE, host.routes.single())
            assertEquals(1, engine.requests.size)
            assertTrue(
                engine.requests
                    .single()
                    .modifiers
                    .isEmpty(),
            )
            assertTrue(
                (result as Outcome.Success<CommandResult<String>>)
                    .value.value
                    .contains("phase=base"),
            )
        }

    @Test
    fun modifiersRouteAgainAndReportTheChange() =
        runTest {
            val host = FakeMapHost(CAMERA)
            val engine = FakeEngine()

            val result =
                command(host, engine).execute(
                    JsonObject(
                        mapOf(
                            "area" to JsonPrimitive("fixture"),
                            "modifiers" to JsonPrimitive(true),
                        ),
                    ),
                )

            assertTrue(result is Outcome.Success)
            assertEquals(listOf(BASE, MODIFIED), host.routes)
            assertEquals(2, engine.requests.size)
            assertEquals(2, engine.requests[1].modifiers.size)
            assertEquals(
                setOf(RouteModifierKind.Multiplier, RouteModifierKind.AddedSeconds),
                engine.requests[1]
                    .modifiers
                    .mapNotNull { it.kind }
                    .toSet(),
            )
            val summary =
                (result as Outcome.Success<CommandResult<String>>).value.value
            assertTrue(summary.contains("phase=modified"))
            assertTrue(summary.contains("changed=true"))
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
            val session = MapSessionHolder()

            val result =
                TestWalkCommand(session, { FakeEngine() }, { dataSources() }, log = {}).execute(JsonNull)

            assertEquals(Outcome.Failure(AppError.NotReady), result)
        }

    private fun command(
        host: FakeMapHost,
        engine: RoutingEngine,
    ): TestWalkCommand {
        val session = MapSessionHolder()
        session.bind(host)
        return TestWalkCommand(session, { engine }, { dataSources() }, log = {})
    }

    private fun dataSources(): DataSourceAccess {
        val pluginHost =
            PluginHost(
                plugins = GeneratedPlugins.plugins,
                streams = emptyList(),
                commands = emptyList(),
                dispatcher = Dispatchers.Unconfined,
            )
        return DataSourceAccess(ProviderRegistry(pluginHost))
    }

    private class FakeEngine : RoutingEngine {
        val requests = mutableListOf<RouteRequest>()

        override val contract: ProviderContract = RoutingEngineContract.REFERENCE

        override fun capabilities(): RoutingCapabilities =
            RoutingCapabilities(
                profiles = setOf(RouteProfile.Walk),
                modifierKinds = setOf(RouteModifierKind.Multiplier, RouteModifierKind.AddedSeconds),
                maxAlternatives = 2,
                maxDistanceMeters = 250_000.0,
            )

        override suspend fun route(
            request: RouteRequest,
            cancellation: CancellationHandle,
        ): Outcome<List<Route>> {
            requests += request
            return Outcome.Success(listOf(if (request.modifiers.isEmpty()) BASE else MODIFIED))
        }
    }

    private companion object {
        val CAMERA = Camera(center = GeoPoint(49.94, 23.94), zoom = 13.0)
        val BASE =
            Route(
                geometry =
                    listOf(
                        GeoPoint(49.93847, 23.94023),
                        GeoPoint(49.94221, 23.94495),
                    ),
                distanceMeters = 558.0,
                durationSeconds = 394.0,
            )
        val MODIFIED =
            Route(
                geometry =
                    listOf(
                        GeoPoint(49.93847, 23.94023),
                        GeoPoint(49.9300, 23.9300),
                        GeoPoint(49.94221, 23.94495),
                    ),
                distanceMeters = 4_868.0,
                durationSeconds = 3_438.0,
            )
    }
}
