package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.app.generated.GeneratedStreams
import nl.r1a.grassmap.core.CommandDispatchers
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.ProviderState
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugins.example.ThrowingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedPluginRegistryTest {
    @Test
    fun generatedRegistryContainsTheExampleContributions() {
        assertTrue(
            GeneratedPlugins.plugins.any { it.manifest.id == PluginId("example") },
        )
        assertTrue(
            GeneratedPlugins.commands.any { it.id == CommandId("example.set_value") },
        )
        assertTrue(
            GeneratedPlugins.providers.any {
                it.contract == ProviderContract("example.throwing", "1.0.0")
            },
        )
        assertTrue(GeneratedPlugins.declaredCommandImplementations.isNotEmpty())
        assertTrue(
            GeneratedStreams.streams.any { it.id == StreamId("example.value") },
        )
    }

    @Test
    fun generatedRegistryContainsTheMapControlsPlugin() {
        assertTrue(
            GeneratedPlugins.plugins.any { it.manifest.id == PluginId("map.controls") },
        )
        assertTrue(
            GeneratedStreams.streams.any { it.id == StreamId("map.controls.mode") },
        )
    }

    @Test
    fun generatedRegistryExposesTheControlAllowlistWithoutActivation() {
        val command = GeneratedPlugins.commands.single { it.id == CommandId("example.set_value") }

        assertTrue(command.allowlistedForControl)
        assertEquals(CommandDispatcher.DEFAULT, command.dispatcher)
    }

    @Test
    fun exampleCommandRunsThroughTheControlAdapterAndDirectly() =
        runTest {
            val container = testContainer(testScheduler)

            val viaAdapter =
                TestControlAdapter(container.commandBus)
                    .invoke("example.set_value", JsonPrimitive(11))
            assertEquals(Outcome.Success(JsonPrimitive(11)), viaAdapter)

            val direct =
                container.commandBus.invoke(
                    CommandId("example.set_value"),
                    JsonPrimitive(12),
                )
            assertEquals(Outcome.Success(JsonPrimitive(12)), direct)

            assertEquals(
                12,
                container.streamAccess.subscribe<Int>(StreamId("example.value")).first(),
            )
            assertEquals(Outcome.Success(Unit), container.commandBus.undo())
            assertEquals(
                11,
                container.streamAccess.subscribe<Int>(StreamId("example.value")).first(),
            )
            assertEquals(Outcome.Success(Unit), container.commandBus.redo())
            assertEquals(
                12,
                container.streamAccess.subscribe<Int>(StreamId("example.value")).first(),
            )
        }

    @Test
    fun throwingExampleProviderIsDisabledAfterThreeFailures() =
        runTest {
            val container = testContainer(testScheduler)
            val pluginId = PluginId("example")
            val contract = ProviderContract("example.throwing", "1.0.0")

            repeat(3) {
                val result =
                    container.providerRegistry.invoke(pluginId, contract) { provider ->
                        (provider as ThrowingProvider).fail()
                    }
                assertEquals(Outcome.Failure(AppError.PluginFailed(pluginId)), result)
            }

            val status = container.providerRegistry.statusOf(pluginId, contract)
            assertEquals(ProviderState.DISABLED, status?.state)
            assertEquals(3, status?.failures)
        }

    private fun testContainer(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler): AppContainer {
        val dispatcher = StandardTestDispatcher(scheduler)
        return AppContainer(
            strings = KeyOnlyStrings,
            dispatchers =
                CommandDispatchers(
                    main = dispatcher,
                    default = dispatcher,
                    io = dispatcher,
                ),
        )
    }

    private object KeyOnlyStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = key

        override fun contains(key: String): Boolean = false
    }
}
