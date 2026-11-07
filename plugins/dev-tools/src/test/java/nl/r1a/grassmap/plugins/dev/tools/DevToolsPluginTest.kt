package nl.r1a.grassmap.plugins.dev.tools

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevToolsPluginTest {
    @Test
    fun activationRegistersTwoCommandsAndOneStateStream() =
        runTest {
            val context = context(FakeDevToolsAccess())
            val plugin = DevToolsPlugin(UnconfinedTestDispatcher(testScheduler))

            assertEquals(Outcome.Success(Unit), plugin.activate(context))

            assertEquals(
                setOf(
                    DevToolsContract.startReplayCommand,
                    DevToolsContract.stopReplayCommand,
                ),
                context.commands.map { it.id }.toSet(),
            )
            assertEquals(
                setOf(DevToolsContract.stateStream),
                context.streams.map { it.id }.toSet(),
            )
            assertTrue(context.providers.isEmpty())
            plugin.deactivate()
        }

    @Test
    fun thePanelStateReadsTheInjectedSample() =
        runTest {
            val fake =
                FakeDevToolsAccess(
                    framesPerSecond = 58.5,
                    usedMemoryBytes = 42_000_000L,
                )
            val context = context(fake)
            val plugin = DevToolsPlugin(UnconfinedTestDispatcher(testScheduler))
            plugin.activate(context)

            val state = context.stateFlow().first()

            assertFalse(state.replayActive)
            assertEquals(58.5, state.framesPerSecond, 0.0)
            assertEquals(42_000_000L, state.usedMemoryBytes)
            plugin.deactivate()
        }

    @Test
    fun startAndStopChangeThePublishedState() =
        runTest {
            val fake = FakeDevToolsAccess()
            val context = context(fake)
            val plugin = DevToolsPlugin(UnconfinedTestDispatcher(testScheduler))
            plugin.activate(context)

            val start = context.commands.single { it.id == DevToolsContract.startReplayCommand }
            val started = start.invokeRaw(JsonNull)
            assertTrue(fake.replayActive)
            assertTrue(decode(started).replayActive)
            assertTrue(context.stateFlow().first().replayActive)

            val stop = context.commands.single { it.id == DevToolsContract.stopReplayCommand }
            val stopped = stop.invokeRaw(JsonNull)
            assertFalse(fake.replayActive)
            assertFalse(decode(stopped).replayActive)
            assertFalse(context.stateFlow().first().replayActive)
            plugin.deactivate()
        }

    private fun decode(outcome: Outcome<CommandResult<JsonElement>>): DevToolsPanelState =
        when (outcome) {
            is Outcome.Success -> {
                Json.decodeFromJsonElement(
                    DevToolsPanelState.serializer(),
                    outcome.value.value,
                )
            }

            is Outcome.Failure -> {
                throw AssertionError("command failed: ${outcome.error}")
            }
        }
}
