package nl.r1a.grassmap.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.PluginManagement
import nl.r1a.grassmap.plugin.api.contract.SetEnabledParams
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.manifest.ProviderDeclaration
import nl.r1a.grassmap.plugin.api.model.PluginState
import nl.r1a.grassmap.plugin.api.provider.Provider
import nl.r1a.grassmap.plugin.api.widget.WidgetDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSwitchTest {
    private val id = PluginId(TEST_PLUGIN_ID)
    private val doubleId = CommandId("$TEST_PLUGIN_ID.double")
    private val provider = ThrowingTestProvider()
    private val manifest =
        testManifest(commands = listOf(testCommandDeclaration("$TEST_PLUGIN_ID.double")))
    private val richManifest =
        testManifest(
            commands = listOf(testCommandDeclaration("$TEST_PLUGIN_ID.double")),
            streams = listOf(testStreamDeclaration()),
            widgets = listOf(testWidgetDeclaration()),
            providers = listOf(ProviderDeclaration(contract = provider.contract.name, version = provider.contract.version)),
        )

    private class Probe {
        var activations = 0
        var deactivations = 0
        var failActivation = false

        /** Runs inside activation before the contributions are reported; tests hold the activation here. */
        var whileActivating: suspend () -> Unit = {}
    }

    private fun TestScope.host(
        probe: Probe = Probe(),
        store: PluginEnabledStore = MemoryEnabledStore(),
        switchable: Set<PluginId> = setOf(id),
    ): PluginHost =
        testHost(testScheduler, manifest, enabledStore = store, switchable = switchable) {
            ProbePlugin(probe)
        }

    private fun TestScope.richHost(
        probe: Probe = Probe(),
        store: PluginEnabledStore = MemoryEnabledStore(),
    ): PluginHost =
        testHost(
            testScheduler,
            richManifest,
            streams = listOf(testGeneratedStream()),
            enabledStore = store,
            switchable = setOf(id),
        ) {
            ProbePlugin(
                probe,
                streams = listOf(TestStream().descriptor),
                providers = listOf(provider),
                widgets = listOf(TestWidget.descriptor()),
            )
        }

    private class ProbePlugin(
        private val probe: Probe,
        private val streams: List<StreamDescriptor<*>> = emptyList(),
        private val providers: List<Provider> = emptyList(),
        private val widgets: List<WidgetDescriptor> = emptyList(),
    ) : Plugin {
        override suspend fun activate(context: PluginContext): Outcome<Unit> {
            probe.activations++
            if (probe.failActivation) {
                return Outcome.Failure(AppError.PluginFailed(PluginId(TEST_PLUGIN_ID)))
            }
            probe.whileActivating()
            context.addCommand(DoublingCommand())
            streams.forEach(context::addStream)
            providers.forEach(context::addProvider)
            widgets.forEach(context::addWidget)
            return Outcome.Success(Unit)
        }

        override fun deactivate() {
            probe.deactivations++
        }
    }

    @Test
    fun switchablePluginIsReportedAsSwitchableAndEnabled() =
        runTest {
            val status = host().statusOf(id)
            assertTrue(status?.switchable == true)
            assertTrue(status?.enabled == true)
        }

    @Test
    fun disablingDeactivatesAndAnswersDisabled() =
        runTest {
            val probe = Probe()
            val host = host(probe)
            assertTrue(host.command(doubleId) is Outcome.Success)

            val result = host.setEnabled(id, enabled = false)

            assertEquals(PluginState.DEACTIVATED, (result as Outcome.Success).value.state)
            assertFalse(result.value.enabled)
            assertEquals(1, probe.deactivations)
            assertEquals(Outcome.Failure(AppError.PluginDisabled(id)), host.command(doubleId))
            assertEquals(1, probe.activations)
        }

    @Test
    fun disabledPluginAnswersPluginDisabledForItsStreamWidgetAndProvider() =
        runTest {
            val host = richHost()
            assertTrue(host.stream(TestStream.STREAM_ID) is Outcome.Success)
            assertTrue(host.widget(TestWidget.WIDGET_ID) is Outcome.Success)
            assertTrue(host.provider(id, provider.contract) is Outcome.Success)

            host.setEnabled(id, enabled = false)

            assertEquals(Outcome.Failure(AppError.PluginDisabled(id)), host.stream(TestStream.STREAM_ID))
            assertEquals(Outcome.Failure(AppError.PluginDisabled(id)), host.widget(TestWidget.WIDGET_ID))
            assertEquals(Outcome.Failure(AppError.PluginDisabled(id)), host.provider(id, provider.contract))
        }

    @Test
    fun aDisableWhileThePluginIsActivatingWins() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val probe =
                Probe().apply {
                    whileActivating = {
                        started.complete(Unit)
                        gate.await()
                    }
                }
            val host = host(probe)
            val lookup = async { host.command(doubleId) }
            started.await()

            host.setEnabled(id, enabled = false)
            gate.complete(Unit)

            assertEquals(Outcome.Failure(AppError.PluginDisabled(id)), lookup.await())
            assertEquals(1, probe.activations)
            assertEquals(1, probe.deactivations)
            assertEquals(PluginState.DEACTIVATED, host.statusOf(id)?.state)
            assertFalse(host.statusOf(id)?.enabled == true)
        }

    @Test
    fun theLastOfTwoConcurrentTogglesWins() =
        runTest {
            val store = MemoryEnabledStore()
            val host = host(store = store)

            val off = launch { host.setEnabled(id, enabled = false) }
            val on = launch { host.setEnabled(id, enabled = true) }
            off.join()
            on.join()

            assertTrue(host.statusOf(id)?.enabled == true)
            assertEquals(emptySet<PluginId>(), store.stored)
        }

    @Test
    fun enablingRestoresTheCommands() =
        runTest {
            val probe = Probe()
            val host = host(probe)
            host.setEnabled(id, enabled = false)

            host.setEnabled(id, enabled = true)

            assertTrue(host.command(doubleId) is Outcome.Success)
            assertTrue(host.statusOf(id)?.enabled == true)
        }

    @Test
    fun disabledStateSurvivesARestart() =
        runTest {
            val store = MemoryEnabledStore()
            host(store = store).setEnabled(id, enabled = false)

            val restarted = host(store = store)

            assertEquals(Outcome.Failure(AppError.PluginDisabled(id)), restarted.command(doubleId))
            restarted.load()
            assertFalse(restarted.statusOf(id)?.enabled == true)
        }

    @Test
    fun enabledStateSurvivesARestart() =
        runTest {
            val store = MemoryEnabledStore()
            host(store = store).setEnabled(id, enabled = false)
            host(store = store).setEnabled(id, enabled = true)

            assertTrue(host(store = store).command(doubleId) is Outcome.Success)
        }

    @Test
    fun aStoreThatCannotBeReadLeavesEveryPluginOnAndRefusesSwitches() =
        runTest {
            for (refusal in listOf(AppError.CorruptData, AppError.NewerSchema(found = 2, supported = 1))) {
                val store = MemoryEnabledStore(loadRefusal = refusal)
                val host = host(store = store)

                assertTrue(host.command(doubleId) is Outcome.Success)
                assertEquals(Outcome.Failure(refusal), host.setEnabled(id, enabled = false))
                assertTrue(host.statusOf(id)?.enabled == true)
                assertEquals("no write over a refused file", 0, store.saves)
            }
        }

    @Test
    fun storedIdOfAnEssentialPluginIsIgnored() =
        runTest {
            host().setEnabled(id, enabled = false)

            val essential = host(switchable = emptySet())

            assertTrue(essential.command(doubleId) is Outcome.Success)
        }

    @Test
    fun essentialPluginCannotBeSwitched() =
        runTest {
            val host = host(switchable = emptySet())

            assertEquals(Outcome.Failure(AppError.Unsupported), host.setEnabled(id, enabled = false))
        }

    @Test
    fun unknownPluginIsNotFound() =
        runTest {
            val result = host().setEnabled(PluginId("missing"), enabled = false)

            assertEquals(Outcome.Failure(AppError.NotFound), result)
        }

    @Test
    fun saveFailureLeavesThePluginRunning() =
        runTest {
            val store = MemoryEnabledStore(saveRefusal = AppError.Internal("core.plugin_enabled_store", "disk full"))
            val host = host(store = store)

            val result = host.setEnabled(id, enabled = false)

            assertTrue((result as Outcome.Failure).error is AppError.Internal)
            assertTrue(host.command(doubleId) is Outcome.Success)
            assertTrue(host.statusOf(id)?.enabled == true)
        }

    @Test
    fun enablingClearsAFailure() =
        runTest {
            val probe = Probe().apply { failActivation = true }
            val host = host(probe)
            assertTrue(host.command(doubleId) is Outcome.Failure)
            probe.failActivation = false
            assertTrue(host.command(doubleId) is Outcome.Failure)

            host.setEnabled(id, enabled = true)

            assertTrue(host.command(doubleId) is Outcome.Success)
        }

    @Test
    fun setEnabledCommandRunsThroughTheBus() =
        runTest {
            val host = host()
            val command = SetPluginEnabledCommand { host }

            val result = command.execute(SetEnabledParams(id, enabled = false))

            assertFalse((result as Outcome.Success).value.value.enabled)
            assertEquals(PluginManagement.setEnabled, command.id)
            assertFalse(command.policy.allowlistedForControl)
        }

    @Test
    fun stateStreamListsEveryPlugin() =
        runTest {
            val host = host()
            val stream = host.stream(PluginManagement.stateStream)

            assertTrue(stream is Outcome.Success)
            assertEquals(listOf(id), host.statuses.value.map { it.pluginId })
        }

    @Test
    fun theStoreHoldsExactlyTheDisabledIds() =
        runTest {
            val store = MemoryEnabledStore()
            val host = host(store = store)

            host.setEnabled(id, enabled = false)
            assertEquals(setOf(id), store.stored)

            host.setEnabled(id, enabled = true)
            assertEquals(emptySet<PluginId>(), store.stored)
            assertEquals(2, store.saves)
        }
}
