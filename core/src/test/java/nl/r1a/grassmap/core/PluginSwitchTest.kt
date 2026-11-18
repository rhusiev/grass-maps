package nl.r1a.grassmap.core

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
import nl.r1a.grassmap.plugin.api.model.PluginState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PluginSwitchTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val id = PluginId(TEST_PLUGIN_ID)
    private val doubleId = CommandId("$TEST_PLUGIN_ID.double")
    private val manifest =
        testManifest(commands = listOf(testCommandDeclaration("$TEST_PLUGIN_ID.double")))

    private fun storeFile(): File = File(folder.root, "settings/plugins.json")

    private class Probe {
        var activations = 0
        var deactivations = 0
        var failActivation = false
    }

    private fun TestScope.host(
        probe: Probe = Probe(),
        store: PluginEnabledStore = FilePluginEnabledStore(storeFile()),
        switchable: Set<PluginId> = setOf(id),
    ): PluginHost =
        testHost(testScheduler, manifest, enabledStore = store, switchable = switchable) {
            ProbePlugin(probe)
        }

    private class ProbePlugin(
        private val probe: Probe,
    ) : Plugin {
        override suspend fun activate(context: PluginContext): Outcome<Unit> {
            probe.activations++
            if (probe.failActivation) {
                return Outcome.Failure(AppError.PluginFailed(PluginId(TEST_PLUGIN_ID)))
            }
            context.addCommand(DoublingCommand())
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
            host().setEnabled(id, enabled = false)

            val restarted = host()

            assertEquals(Outcome.Failure(AppError.PluginDisabled(id)), restarted.command(doubleId))
            restarted.load()
            assertFalse(restarted.statusOf(id)?.enabled == true)
        }

    @Test
    fun enabledStateSurvivesARestart() =
        runTest {
            host().setEnabled(id, enabled = false)
            host().setEnabled(id, enabled = true)

            assertTrue(host().command(doubleId) is Outcome.Success)
        }

    @Test
    fun corruptStoreReadsAsAllEnabled() =
        runTest {
            storeFile().apply {
                parentFile?.mkdirs()
                writeText("{not json")
            }

            assertTrue(host().command(doubleId) is Outcome.Success)
        }

    @Test
    fun wrongVersionStoreReadsAsAllEnabled() =
        runTest {
            storeFile().apply {
                parentFile?.mkdirs()
                writeText("""{"version":99,"disabled":["$TEST_PLUGIN_ID"]}""")
            }

            assertTrue(host().command(doubleId) is Outcome.Success)
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
            val failing =
                object : PluginEnabledStore {
                    override fun loadDisabled(): Set<PluginId> = emptySet()

                    override fun saveDisabled(disabled: Set<PluginId>): Unit = throw IOException("disk full")
                }
            val host = host(store = failing)

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
    fun storeFileHoldsTheDisabledIds() =
        runTest {
            host().setEnabled(id, enabled = false)

            val text = storeFile().readText()

            assertTrue(text.contains("\"$TEST_PLUGIN_ID\""))
            assertFalse(File(storeFile().path + ".part").exists())
        }
}
