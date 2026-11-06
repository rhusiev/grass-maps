package nl.r1a.grassmap.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.plugin.api.command.CommandInvoked
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.PluginState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandBusTest {
    private lateinit var injectedBus: CommandBus

    @Test
    fun commandRunsFromGeneratedRegistry() = runTest {
        val manifest = testManifest(commands = listOf(testCommandDeclaration("test.double")))
        val host = testHost(testScheduler, manifest) { TestPlugin(commands = listOf(DoublingCommand())) }
        val bus = testBus(testScheduler, host, manifest)

        val result = bus.invoke(CommandId("test.double"), JsonPrimitive(21))

        assertEquals(Outcome.Success(JsonPrimitive(42)), result)
    }

    @Test
    fun unknownCommandReturnsNotFound() = runTest {
        val manifest = testManifest()
        val host = testHost(testScheduler, manifest) { TestPlugin() }
        val bus = testBus(testScheduler, host, manifest)

        val result = bus.invoke(CommandId("test.missing"), JsonPrimitive(1))

        assertEquals(Outcome.Failure(AppError.NotFound), result)
    }

    @Test
    fun invalidParametersReturnInvalidInput() = runTest {
        val manifest = testManifest(commands = listOf(testCommandDeclaration("test.double")))
        val host = testHost(testScheduler, manifest) { TestPlugin(commands = listOf(DoublingCommand())) }
        val bus = testBus(testScheduler, host, manifest)

        val result = bus.invoke(CommandId("test.double"), JsonPrimitive("not an int"))

        assertEquals(Outcome.Failure(AppError.InvalidInput), result)
    }

    @Test
    fun commandRequiringAnOffSwitchReturnsSwitchOffWithoutActivating() = runTest {
        var created = 0
        val manifest = testManifest(
            commands = listOf(
                testCommandDeclaration("test.switch", requiresSwitch = SwitchId("network.test")),
            ),
        )
        val host = testHost(testScheduler, manifest) {
            created += 1
            TestPlugin(commands = listOf(SwitchCommand()))
        }
        val bus = testBus(testScheduler, host, manifest, switchEnabled = { false })

        val result = bus.invoke(CommandId("test.switch"), JsonPrimitive(1))

        assertEquals(
            Outcome.Failure(AppError.SwitchOff(SwitchId("network.test"))),
            result,
        )
        assertEquals(0, created)
        assertEquals(PluginState.DECLARED, host.statusOf(PluginId(TEST_PLUGIN_ID))?.state)
    }

    @Test
    fun commandRequiringAnOnSwitchRuns() = runTest {
        val manifest = testManifest(
            commands = listOf(
                testCommandDeclaration("test.switch", requiresSwitch = SwitchId("network.test")),
            ),
        )
        val host = testHost(testScheduler, manifest) { TestPlugin(commands = listOf(SwitchCommand())) }
        val bus = testBus(testScheduler, host, manifest, switchEnabled = { true })

        val result = bus.invoke(CommandId("test.switch"), JsonPrimitive(7))

        assertEquals(Outcome.Success(JsonPrimitive(7)), result)
    }

    @Test
    fun undoAndRedoUseDynamicInverses() = runTest {
        val state = MutableStateFlow(0)
        val manifest = testManifest(
            commands = listOf(testCommandDeclaration("test.set", undoable = true)),
        )
        val host = testHost(testScheduler, manifest) { TestPlugin(commands = listOf(SetCommand(state))) }
        val bus = testBus(testScheduler, host, manifest)

        assertEquals(Outcome.Success(JsonPrimitive(1)), bus.invoke(CommandId("test.set"), JsonPrimitive(1)))
        assertEquals(Outcome.Success(JsonPrimitive(2)), bus.invoke(CommandId("test.set"), JsonPrimitive(2)))
        assertEquals(2, state.value)

        assertEquals(Outcome.Success(Unit), bus.undo())
        assertEquals(1, state.value)
        assertEquals(Outcome.Success(Unit), bus.redo())
        assertEquals(2, state.value)
        assertEquals(Outcome.Success(Unit), bus.undo())
        assertEquals(Outcome.Success(Unit), bus.undo())
        assertEquals(0, state.value)
        assertEquals(Outcome.Failure(AppError.NotReady), bus.undo())
        assertEquals(Outcome.Success(Unit), bus.redo())
        assertEquals(1, state.value)
    }

    @Test
    fun newCommandInvalidatesRedoEvenWhenNotUndoable() = runTest {
        val state = MutableStateFlow(0)
        val manifest = testManifest(
            commands = listOf(
                testCommandDeclaration("test.set", undoable = true),
                testCommandDeclaration("test.double"),
            ),
        )
        val host = testHost(testScheduler, manifest) {
            TestPlugin(commands = listOf(SetCommand(state), DoublingCommand()))
        }
        val bus = testBus(testScheduler, host, manifest)

        assertEquals(Outcome.Success(JsonPrimitive(1)), bus.invoke(CommandId("test.set"), JsonPrimitive(1)))
        assertEquals(Outcome.Success(Unit), bus.undo())
        assertEquals(0, state.value)

        assertEquals(Outcome.Success(JsonPrimitive(2)), bus.invoke(CommandId("test.double"), JsonPrimitive(1)))

        assertEquals(Outcome.Failure(AppError.NotReady), bus.redo())
    }

    @Test
    fun undoCanDispatchAnotherCommandThroughTheBus() = runTest {
        val state = MutableStateFlow(0)
        val manifest = testManifest(
            commands = listOf(
                testCommandDeclaration("test.set", undoable = true),
                testCommandDeclaration("test.chain", undoable = true),
                testCommandDeclaration("test.nest", undoable = true),
            ),
        )
        val host = testHost(testScheduler, manifest) {
            TestPlugin(
                commands = listOf(
                    SetCommand(state),
                    ChainCommand(),
                    NestingCommand { id, params -> injectedBus.invoke(id, params) },
                ),
            )
        }
        injectedBus = testBus(testScheduler, host, manifest)

        assertEquals(
            Outcome.Success(JsonPrimitive(5)),
            injectedBus.invoke(CommandId("test.chain"), JsonPrimitive(5)),
        )
        assertEquals(Outcome.Success(Unit), injectedBus.undo())
        assertEquals(5, state.value)
        assertEquals(Outcome.Success(Unit), injectedBus.undo())
        assertEquals(0, state.value)
        assertEquals(Outcome.Failure(AppError.NotReady), injectedBus.undo())
    }

    @Test
    fun cancellationInsideACommandReturnsCancelled() = runTest {
        val manifest = testManifest(commands = listOf(testCommandDeclaration("test.cancel")))
        val host = testHost(testScheduler, manifest) {
            TestPlugin(commands = listOf(SelfCancellingCommand()))
        }
        val bus = testBus(testScheduler, host, manifest)

        val result = bus.invoke(CommandId("test.cancel"), JsonPrimitive(1))

        assertEquals(Outcome.Failure(AppError.Cancelled), result)
    }

    @Test
    fun cancelledDispatchStillEmitsOneEvent() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val manifest = testManifest(commands = listOf(testCommandDeclaration("test.blocking")))
        val host = testHost(testScheduler, manifest) {
            TestPlugin(commands = listOf(BlockingCommand(started, gate)))
        }
        val bus = testBus(testScheduler, host, manifest)
        val events = mutableListOf<CommandInvoked>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { events += it }
        }
        runCurrent()

        val job = launch { bus.invoke(CommandId("test.blocking"), JsonPrimitive(1)) }
        started.await()
        job.cancelAndJoin()

        assertEquals(1, events.size)
        assertFalse(events.single().succeeded)
        assertEquals(AppError.Cancelled, events.single().error)
        gate.complete(Unit)
    }

    @Test
    fun pluginActivatesLazilyOnFirstCommand() = runTest {
        var created = 0
        val manifest = testManifest(commands = listOf(testCommandDeclaration("test.double")))
        val host = testHost(testScheduler, manifest) {
            created += 1
            TestPlugin(commands = listOf(DoublingCommand()))
        }
        val bus = testBus(testScheduler, host, manifest)

        assertEquals(0, created)
        assertEquals(PluginState.DECLARED, host.statusOf(PluginId(TEST_PLUGIN_ID))?.state)

        bus.invoke(CommandId("test.double"), JsonPrimitive(1))
        bus.invoke(CommandId("test.double"), JsonPrimitive(2))

        assertEquals(1, created)
        val status = host.statusOf(PluginId(TEST_PLUGIN_ID))
        assertEquals(PluginState.RUNNING, status?.state)
        assertNotNull(status?.activationMillis)
    }

    @Test
    fun emitsCommandInvokedEvent() = runTest {
        val manifest = testManifest(commands = listOf(testCommandDeclaration("test.double")))
        val host = testHost(testScheduler, manifest) { TestPlugin(commands = listOf(DoublingCommand())) }
        val bus = testBus(testScheduler, host, manifest)
        val events = mutableListOf<CommandInvoked>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { events += it }
        }
        runCurrent()

        bus.invoke(CommandId("test.double"), JsonPrimitive(4))

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(CommandId("test.double"), event.commandId)
        assertEquals(PluginId(TEST_PLUGIN_ID), event.pluginId)
        assertTrue(event.succeeded)
        assertEquals(null, event.error)
    }

    @Test
    fun cancellableCommandCancelsItsPredecessor() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val manifest = testManifest(commands = listOf(testCommandDeclaration("test.blocking")))
        val host = testHost(testScheduler, manifest) {
            TestPlugin(commands = listOf(BlockingCommand(started, gate)))
        }
        val bus = testBus(testScheduler, host, manifest)

        val first = async {
            bus.invoke(CommandId("test.blocking"), JsonPrimitive(1))
        }
        started.await()

        val second = bus.invoke(CommandId("test.blocking"), JsonPrimitive(2))

        assertEquals(Outcome.Success(JsonPrimitive(2)), second)
        assertEquals(Outcome.Failure(AppError.Cancelled), first.await())
        gate.complete(Unit)
    }

    @Test
    fun activationExceptionIsAttributedAndDoesNotRetry() = runTest {
        var created = 0
        val manifest = testManifest(commands = listOf(testCommandDeclaration("test.double")))
        val host = testHost(testScheduler, manifest) {
            created += 1
            TestPlugin(onActivate = { throw IllegalStateException("activation failure") })
        }
        val bus = testBus(testScheduler, host, manifest)

        val first = bus.invoke(CommandId("test.double"), JsonPrimitive(1))
        val second = bus.invoke(CommandId("test.double"), JsonPrimitive(1))

        assertEquals(
            Outcome.Failure(AppError.PluginFailed(PluginId(TEST_PLUGIN_ID))),
            first,
        )
        assertEquals(first, second)
        assertEquals(1, created)
        assertEquals(PluginState.FAILED, host.statusOf(PluginId(TEST_PLUGIN_ID))?.state)
    }
}
