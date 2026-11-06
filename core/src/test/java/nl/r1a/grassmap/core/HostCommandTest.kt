package nl.r1a.grassmap.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.CommandInvoker
import nl.r1a.grassmap.plugin.api.manifest.GeneratedCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostCommandTest {
    @Test
    fun hostCommandIsReturnedDirectly() =
        runTest {
            val command = HostDoublingCommand()
            val host = hostWith(testScheduler, command)

            assertEquals(Outcome.Success(command), host.command(command.id))
            assertEquals(null, host.statusOf(PluginId("core")))
        }

    @Test
    fun commandInvokerDispatchesAHostCommandWithoutActivation() =
        runTest {
            val command = HostDoublingCommand()
            val host = hostWith(testScheduler, command)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val invoker: CommandInvoker =
                CommandBus(
                    host = host,
                    commands = listOf(generated(command)),
                    dispatchers =
                        CommandDispatchers(
                            main = dispatcher,
                            default = dispatcher,
                            io = dispatcher,
                        ),
                )

            val result = invoker.invoke(command.id, JsonPrimitive(21))

            assertEquals(Outcome.Success(JsonPrimitive(42)), result)
            assertTrue(host.manifests().isEmpty())
        }

    @Test
    fun unknownCommandStillReturnsNotFound() =
        runTest {
            val host = hostWith(testScheduler, HostDoublingCommand())

            assertEquals(
                Outcome.Failure(AppError.NotFound),
                host.command(CommandId("core.test.missing")),
            )
        }

    private fun hostWith(
        scheduler: TestCoroutineScheduler,
        command: Command<*, *>,
    ): PluginHost =
        PluginHost(
            plugins = emptyList(),
            streams = emptyList(),
            commands = emptyList(),
            hostCommands = listOf(command),
            dispatcher = StandardTestDispatcher(scheduler),
        )

    private fun generated(command: Command<*, *>): GeneratedCommand =
        GeneratedCommand(
            id = command.id,
            pluginId = PluginId("core"),
            dispatcher = command.policy.dispatcher,
        )
}
