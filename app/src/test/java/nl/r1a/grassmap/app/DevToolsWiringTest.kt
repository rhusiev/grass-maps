package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.app.generated.GeneratedStreams
import nl.r1a.grassmap.core.CommandDispatchers
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.DevToolsMetrics
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugins.dev.tools.DevToolsContract
import nl.r1a.grassmap.plugins.dev.tools.DevToolsPanelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevToolsWiringTest {
    @Test
    fun theReleaseRegistryShipsTheDevelopmentToolsPlugin() {
        assertTrue(
            GeneratedPlugins.plugins.any { it.manifest.id == PluginId(DevToolsContract.PLUGIN_ID) },
        )
        assertTrue(
            GeneratedPlugins.commands.any { command ->
                command.id == DevToolsContract.startReplayCommand &&
                    command.requiresSwitch == DevToolsContract.aiControlSwitch
            },
        )
        assertTrue(
            GeneratedStreams.streams.any { it.id == DevToolsContract.stateStream },
        )
    }

    @Test
    fun startAndStopChangeThePanelStateAndReadTheInjectedSample() =
        runTest {
            val devTools =
                FakeDevToolsRuntime(
                    framesPerSecond = 57.5,
                    usedMemoryBytes = 90_000_000L,
                )
            val container = testContainer(testScheduler, aiControlEnabled = true, devTools = devTools)

            assertTrue(container.devToolsVisible)
            assertFalse(state(container).replayActive)
            assertEquals(57.5, state(container).framesPerSecond, 0.0)
            assertEquals(90_000_000L, state(container).usedMemoryBytes)

            assertTrue(container.commandBus.invoke(DevToolsContract.startReplayCommand) is Outcome.Success)
            assertTrue(devTools.active)
            assertTrue(state(container).replayActive)

            assertTrue(container.commandBus.invoke(DevToolsContract.stopReplayCommand) is Outcome.Success)
            assertFalse(devTools.active)
            assertFalse(state(container).replayActive)
        }

    @Test
    fun theSwitchOffRefusesTheCommandWithoutStartingTheReplay() =
        runTest {
            val devTools = FakeDevToolsRuntime()
            val container = testContainer(testScheduler, aiControlEnabled = false, devTools = devTools)

            assertFalse(container.devToolsVisible)
            assertEquals(
                Outcome.Failure(AppError.SwitchOff(DevToolsContract.aiControlSwitch)),
                container.commandBus.invoke(DevToolsContract.startReplayCommand),
            )
            assertFalse(devTools.active)
        }

    private suspend fun state(container: AppContainer): DevToolsPanelState =
        container.streamAccess.subscribe<DevToolsPanelState>(DevToolsContract.stateStream).first()

    private fun testContainer(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        aiControlEnabled: Boolean,
        devTools: DevToolsRuntime,
    ): AppContainer {
        val dispatcher = StandardTestDispatcher(scheduler)
        return AppContainer(
            strings = KeyOnlyStrings,
            dispatchers =
                CommandDispatchers(
                    main = dispatcher,
                    default = dispatcher,
                    io = dispatcher,
                ),
            devTools = devTools,
            aiControlEnabled = { aiControlEnabled },
        )
    }

    private class FakeDevToolsRuntime(
        private val framesPerSecond: Double = 60.0,
        private val usedMemoryBytes: Long = 0L,
    ) : DevToolsRuntime {
        var active: Boolean = false
            private set

        override fun onFrame() = Unit

        override fun isReplayActive(): Boolean = active

        override fun metrics(): DevToolsMetrics = DevToolsMetrics(framesPerSecond, usedMemoryBytes)

        override suspend fun startReplay(): Outcome<Unit> {
            active = true
            return Outcome.Success(Unit)
        }

        override fun stopReplay() {
            active = false
        }
    }

    private object KeyOnlyStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = key

        override fun contains(key: String): Boolean = false
    }
}
