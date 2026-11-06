package nl.r1a.grassmap.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.manifest.CommandDeclaration
import nl.r1a.grassmap.plugin.api.manifest.GeneratedCommand
import nl.r1a.grassmap.plugin.api.manifest.GeneratedStream
import nl.r1a.grassmap.plugin.api.manifest.PluginContributions
import nl.r1a.grassmap.plugin.api.manifest.PluginFactory
import nl.r1a.grassmap.plugin.api.manifest.PluginManifest
import nl.r1a.grassmap.plugin.api.manifest.StreamDeclaration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlEndpointTest {
    @Test
    fun catalogMergesManifestDocsWithGeneratedPolicy() {
        val declaration =
            CommandDeclaration(
                id = CommandId("test.a"),
                params = "kotlin.Int",
                result = "kotlin.Int",
                doc = "Docs for a.",
                undoable = true,
                requiresSwitch = SwitchId("network.test"),
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
                implementation = "test.ACommand",
            )
        val generated =
            GeneratedCommand(
                id = declaration.id,
                pluginId = PluginId("test"),
                undoable = declaration.undoable,
                requiresSwitch = declaration.requiresSwitch,
                allowlistedForControl = declaration.allowlistedForControl,
                dispatcher = declaration.dispatcher,
            )
        val catalog = GeneratedControlCatalog(listOf(generated), listOf(manifest(listOf(declaration))))

        val command = catalog.command("test.a")

        assertEquals("Docs for a.", command?.doc)
        assertEquals(PluginId("test"), command?.plugin)
        assertTrue(command?.allowlisted == true)
        assertEquals(SwitchId("network.test"), command?.requiresSwitch)
        assertEquals(CommandDispatcher.IO, command?.dispatcher)
        assertEquals(1, catalog.commands().size)
        assertNull(catalog.command("test.missing"))
    }

    @Test
    fun catalogUsesTheGeneratedPolicyNotTheManifestOne() {
        val declaration =
            CommandDeclaration(
                id = CommandId("test.a"),
                params = "kotlin.Int",
                result = "kotlin.Int",
                doc = "Docs for a.",
                allowlistedForControl = false,
                implementation = "test.ACommand",
            )
        val generated =
            GeneratedCommand(
                id = declaration.id,
                pluginId = PluginId("test"),
                allowlistedForControl = true,
            )

        val catalog = GeneratedControlCatalog(listOf(generated), listOf(manifest(listOf(declaration))))

        assertTrue(catalog.command("test.a")?.allowlisted == true)
    }

    @Test
    fun opensAndEncodesADeclaredStream() =
        runTest {
            val pluginId = PluginId("test")
            val streamId = StreamId("test.value")
            val state = MutableStateFlow(3)
            val host =
                PluginHost(
                    plugins = listOf(PluginFactory(manifest = streamManifest(streamId)) { StreamPlugin(state, streamId) }),
                    streams =
                        listOf(
                            GeneratedStream(
                                id = streamId,
                                pluginId = pluginId,
                                valueType = VALUE_TYPE,
                                serializer = SERIALIZER,
                            ),
                        ),
                    commands = emptyList(),
                    dispatcher = StandardTestDispatcher(testScheduler),
                )

            val flow = (PluginHostControlStreams(host).open(streamId) as Outcome.Success).value

            assertEquals(JsonPrimitive(3), flow.first())
        }

    @Test
    fun reportsAnUndeclaredStreamAsNotFound() =
        runTest {
            val host =
                PluginHost(
                    plugins = emptyList(),
                    streams = emptyList(),
                    commands = emptyList(),
                    dispatcher = StandardTestDispatcher(testScheduler),
                )

            val outcome = PluginHostControlStreams(host).open(StreamId("test.missing"))

            assertEquals(Outcome.Failure(AppError.NotFound), outcome)
        }

    private fun manifest(commands: List<CommandDeclaration>): PluginManifest =
        PluginManifest(
            id = PluginId("test"),
            name = "plugin.test.name",
            description = "plugin.test.description",
            version = "1.0.0",
            api = "1.0.0",
            implementation = "test.Plugin",
            contributes = PluginContributions(commands = commands),
        )

    private fun streamManifest(streamId: StreamId): PluginManifest =
        PluginManifest(
            id = PluginId("test"),
            name = "plugin.test.name",
            description = "plugin.test.description",
            version = "1.0.0",
            api = "1.0.0",
            implementation = "test.Plugin",
            contributes =
                PluginContributions(
                    streams =
                        listOf(
                            StreamDeclaration(
                                id = streamId,
                                valueType = VALUE_TYPE,
                                serializer = SERIALIZER,
                            ),
                        ),
                ),
            activation = listOf("stream"),
        )

    private class StreamPlugin(
        private val state: MutableStateFlow<Int>,
        private val streamId: StreamId,
    ) : Plugin {
        override suspend fun activate(context: PluginContext): Outcome<Unit> {
            context.addStream(
                StreamDescriptor(
                    id = streamId,
                    serializer = Int.serializer(),
                    flow = state,
                ),
            )
            return Outcome.Success(Unit)
        }
    }

    private companion object {
        const val VALUE_TYPE = "kotlin.Int"
        const val SERIALIZER = "kotlinx.serialization.builtins.IntSerializer"
    }
}
