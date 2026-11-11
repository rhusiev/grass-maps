package nl.r1a.grassmap.plugins.nav.simulator

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandJson
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.routing.RouteJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plugin's registration and the two commands, through their raw JSON form. */
class NavSimulatorPluginTest {
    @Test
    fun activationDeclaresTheCommandsAndTheStatusStream() =
        runTest {
            val context = RecordingContext(FakeServices(routePlanState()))
            val plugin = NavSimulatorPlugin(StandardTestDispatcher(testScheduler))

            assertEquals(Outcome.Success(Unit), plugin.activate(context))

            assertEquals(NavSimulatorContract.commands.toSet(), context.commands.map { it.id }.toSet())
            for (command in context.commands) {
                assertEquals(false, command.policy.undoable)
                assertEquals(true, command.policy.allowlistedForControl)
            }
            val status = context.streams.single { stream -> stream.id == NavSimulatorContract.statusStream }
            assertEquals(NavSimulatorContract.STATUS_VALUE_TYPE, status.valueType)
            assertEquals(
                NavSimulatorContract.STATUS_VALUE_TYPE,
                SimulatorStatus.serializer().descriptor.serialName,
            )
            assertTrue(context.providers.isEmpty())
            assertTrue(context.widgets.isEmpty())

            plugin.deactivate()
        }

    @Test
    fun startRunsOnTheCurrentPlannerRouteAndStopEndsIt() =
        runTest {
            val route = acceptanceRoute()
            val context = RecordingContext(FakeServices(routePlanState(route)))
            val plugin = NavSimulatorPlugin(StandardTestDispatcher(testScheduler))
            plugin.activate(context)

            val started = status(context, NavSimulatorContract.start, JsonObject(emptyMap()))

            assertTrue(started is Outcome.Success)
            val running = started.valueOrFail()
            assertEquals(SimulatorPhase.Following, running.phase)
            assertEquals(1, running.fixCount)
            assertTrue(running.running)
            assertFalse(running.arrived)
            assertTrue(running.remainingDistanceMeters > route.distanceMeters - 2.0)
            assertEquals(NavSimulatorContract.SPEED_KMH, running.speedKmh, 0.0)

            val stopped = status(context, NavSimulatorContract.stop, JsonObject(emptyMap()))

            assertTrue(stopped is Outcome.Success)
            val final = stopped.valueOrFail()
            assertEquals(SimulatorPhase.Stopped, final.phase)
            assertFalse(final.arrived)
            assertFalse(final.running)

            plugin.deactivate()
        }

    @Test
    fun startAcceptsARouteInTheStableJsonForm() =
        runTest {
            val route = acceptanceRoute()
            val context = RecordingContext(FakeServices())
            val plugin = NavSimulatorPlugin(StandardTestDispatcher(testScheduler))
            plugin.activate(context)
            val params =
                buildJsonObject {
                    put("route", Json.parseToJsonElement(RouteJson.encode(route)))
                    put("profile", "bike")
                }

            val started = status(context, NavSimulatorContract.start, params)

            assertTrue(started is Outcome.Success)
            val running = started.valueOrFail()
            assertEquals(SimulatorPhase.Following, running.phase)
            assertTrue(running.remainingDistanceMeters > route.distanceMeters - 2.0)

            plugin.deactivate()
        }

    @Test
    fun startWithoutACurrentRouteIsNotFound() =
        runTest {
            val context = RecordingContext(FakeServices(plan = null))
            val plugin = NavSimulatorPlugin(StandardTestDispatcher(testScheduler))
            plugin.activate(context)

            assertEquals(
                Outcome.Failure(AppError.NotFound),
                status(context, NavSimulatorContract.start, JsonObject(emptyMap())),
            )

            plugin.deactivate()
        }

    @Test
    fun theStartPolicyMatchesTheOtherNavigationDebugCommands() =
        runTest {
            val context = RecordingContext(FakeServices())
            val plugin = NavSimulatorPlugin(StandardTestDispatcher(testScheduler))
            plugin.activate(context)

            assertEquals(
                CommandPolicy(
                    undoable = false,
                    allowlistedForControl = true,
                    dispatcher = CommandDispatcher.IO,
                ),
                context.command(NavSimulatorContract.start).policy,
            )

            plugin.deactivate()
        }

    private suspend fun status(
        context: RecordingContext,
        id: CommandId,
        params: JsonElement,
    ): Outcome<SimulatorStatus> =
        when (val result = context.command(id).invokeRaw(params)) {
            is Outcome.Failure -> {
                result
            }

            is Outcome.Success -> {
                Outcome.Success(
                    CommandJson.decodeFromJsonElement(SimulatorStatus.serializer(), result.value.value),
                )
            }
        }
}
