package nl.r1a.grassmap.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.manifest.PluginFactory
import nl.r1a.grassmap.plugin.api.model.PluginState
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginHostTest {
    @Test
    fun concurrentActivationCreatesThePluginOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        var created = 0
        val manifest = testManifest(commands = listOf(testCommandDeclaration("test.double")))
        val host = testHost(testScheduler, manifest) {
            created += 1
            TestPlugin(commands = listOf(DoublingCommand()), onActivate = { gate.await() })
        }

        val first = async { host.ensureActive(PluginId(TEST_PLUGIN_ID)) }
        val second = async { host.ensureActive(PluginId(TEST_PLUGIN_ID)) }
        runCurrent()
        gate.complete(Unit)

        assertEquals(Outcome.Success(Unit), first.await())
        assertEquals(Outcome.Success(Unit), second.await())
        assertEquals(1, created)
        assertEquals(PluginState.RUNNING, host.statusOf(PluginId(TEST_PLUGIN_ID))?.state)
    }

    @Test
    fun suspendedActivationDoesNotBlockAnotherPlugin() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slowId = PluginId("test.slow")
        val fastId = PluginId("test.fast")
        val host = PluginHost(
            plugins = listOf(
                PluginFactory(
                    manifest = testManifest(pluginId = slowId),
                    create = { TestPlugin(onActivate = { gate.await() }) },
                ),
                PluginFactory(
                    manifest = testManifest(pluginId = fastId),
                    create = { TestPlugin() },
                ),
            ),
            streams = emptyList(),
            commands = emptyList(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val slow = async { host.ensureActive(slowId) }
        runCurrent()

        assertEquals(Outcome.Success(Unit), host.ensureActive(fastId))
        assertEquals(PluginState.RUNNING, host.statusOf(fastId)?.state)
        assertEquals(PluginState.ACTIVATING, host.statusOf(slowId)?.state)

        gate.complete(Unit)
        assertEquals(Outcome.Success(Unit), slow.await())
    }

    @Test
    fun manifestPolicyMismatchFailsActivation() = runTest {
        val manifest = testManifest(
            commands = listOf(testCommandDeclaration("test.set", undoable = false)),
        )
        val host = testHost(testScheduler, manifest) {
            TestPlugin(commands = listOf(SetCommand(MutableStateFlow(0))))
        }
        val bus = testBus(testScheduler, host, manifest)

        val result = bus.invoke(CommandId("test.set"), JsonPrimitive(1))

        assertEquals(
            Outcome.Failure(AppError.PluginFailed(PluginId(TEST_PLUGIN_ID))),
            result,
        )
        assertEquals(PluginState.FAILED, host.statusOf(PluginId(TEST_PLUGIN_ID))?.state)
        assertEquals(
            Outcome.Failure(AppError.PluginFailed(PluginId(TEST_PLUGIN_ID))),
            bus.invoke(CommandId("test.set"), JsonPrimitive(2)),
        )
    }
}
