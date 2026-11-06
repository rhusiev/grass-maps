package nl.r1a.grassmap.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.plugin.api.command.CancellationKey
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandInvocation
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.manifest.CommandDeclaration
import nl.r1a.grassmap.plugin.api.manifest.GeneratedCommand
import nl.r1a.grassmap.plugin.api.manifest.GeneratedStream
import nl.r1a.grassmap.plugin.api.manifest.PluginContributions
import nl.r1a.grassmap.plugin.api.manifest.PluginFactory
import nl.r1a.grassmap.plugin.api.manifest.PluginManifest
import nl.r1a.grassmap.plugin.api.manifest.ProviderDeclaration
import nl.r1a.grassmap.plugin.api.manifest.StreamDeclaration
import nl.r1a.grassmap.plugin.api.provider.Provider
import nl.r1a.grassmap.plugin.api.provider.ProviderContract

internal const val TEST_PLUGIN_ID = "test"

internal fun testCommandDeclaration(
    id: String,
    undoable: Boolean = false,
    requiresSwitch: SwitchId? = null,
    allowlistedForControl: Boolean = false,
    dispatcher: CommandDispatcher = CommandDispatcher.DEFAULT,
): CommandDeclaration =
    CommandDeclaration(
        id = CommandId(id),
        params = "kotlin.Int",
        result = "kotlin.Int",
        doc = "Test command.",
        undoable = undoable,
        requiresSwitch = requiresSwitch,
        allowlistedForControl = allowlistedForControl,
        dispatcher = dispatcher,
        implementation = "test.Command",
    )

internal fun testGeneratedCommand(
    declaration: CommandDeclaration,
    pluginId: PluginId,
): GeneratedCommand =
    GeneratedCommand(
        id = declaration.id,
        pluginId = pluginId,
        undoable = declaration.undoable,
        requiresSwitch = declaration.requiresSwitch,
        allowlistedForControl = declaration.allowlistedForControl,
        dispatcher = declaration.dispatcher,
    )

internal fun testManifest(
    commands: List<CommandDeclaration> = emptyList(),
    providers: List<ProviderDeclaration> = emptyList(),
    streams: List<StreamDeclaration> = emptyList(),
    pluginId: PluginId = PluginId(TEST_PLUGIN_ID),
): PluginManifest =
    PluginManifest(
        id = pluginId,
        name = "plugin.test.name",
        description = "plugin.test.description",
        version = "1.0.0",
        api = "1.0.0",
        implementation = "test.Plugin",
        contributes =
            PluginContributions(
                commands = commands,
                providers = providers,
                streams = streams,
            ),
        activation = listOf("command"),
    )

internal fun testHost(
    scheduler: TestCoroutineScheduler,
    manifest: PluginManifest,
    streams: List<GeneratedStream> = emptyList(),
    create: () -> Plugin,
): PluginHost =
    PluginHost(
        plugins = listOf(PluginFactory(manifest = manifest, create = create)),
        streams = streams,
        commands =
            manifest.contributes.commands.map {
                testGeneratedCommand(it, manifest.id)
            },
        dispatcher = StandardTestDispatcher(scheduler),
    )

internal fun testBus(
    scheduler: TestCoroutineScheduler,
    host: PluginHost,
    manifest: PluginManifest,
    switchEnabled: (SwitchId) -> Boolean = { false },
): CommandBus {
    val dispatcher = StandardTestDispatcher(scheduler)
    return CommandBus(
        host = host,
        commands =
            manifest.contributes.commands.map {
                testGeneratedCommand(it, manifest.id)
            },
        dispatchers =
            CommandDispatchers(
                main = dispatcher,
                default = dispatcher,
                io = dispatcher,
            ),
        switchEnabled = switchEnabled,
    )
}

internal class TestPlugin(
    private val commands: List<Command<*, *>> = emptyList(),
    private val streams: List<StreamDescriptor<*>> = emptyList(),
    private val providers: List<Provider> = emptyList(),
    private val onActivate: suspend () -> Unit = {},
) : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        onActivate()
        commands.forEach { context.addCommand(it) }
        streams.forEach { context.addStream(it) }
        providers.forEach { context.addProvider(it) }
        return Outcome.Success(Unit)
    }
}

internal class DoublingCommand :
    Command<Int, Int>(
        id = CommandId("$TEST_PLUGIN_ID.double"),
        paramsSerializer = Int.serializer(),
        resultSerializer = Int.serializer(),
        doc = "Doubles the value.",
    ) {
    override suspend fun execute(params: Int): Outcome<CommandResult<Int>> = Outcome.Success(CommandResult(params + params))
}

/** A command the host itself owns; no plugin factory is involved. */
internal class HostDoublingCommand :
    Command<Int, Int>(
        id = CommandId("core.test.host_double"),
        paramsSerializer = Int.serializer(),
        resultSerializer = Int.serializer(),
        doc = "Doubles the value on the Main dispatcher.",
        policy = CommandPolicy(dispatcher = CommandDispatcher.MAIN),
    ) {
    override suspend fun execute(params: Int): Outcome<CommandResult<Int>> = Outcome.Success(CommandResult(params + params))
}

internal class SetCommand(
    private val state: MutableStateFlow<Int>,
) : Command<Int, Int>(
        id = CommandId("$TEST_PLUGIN_ID.set"),
        paramsSerializer = Int.serializer(),
        resultSerializer = Int.serializer(),
        doc = "Sets the test value.",
        policy = CommandPolicy(undoable = true),
    ) {
    override suspend fun execute(params: Int): Outcome<CommandResult<Int>> {
        val previous = state.value
        state.value = params
        return Outcome.Success(
            CommandResult(value = params, inverse = invocation(previous)),
        )
    }
}

internal class SwitchCommand :
    Command<Int, Int>(
        id = CommandId("$TEST_PLUGIN_ID.switch"),
        paramsSerializer = Int.serializer(),
        resultSerializer = Int.serializer(),
        doc = "Requires the test switch.",
        policy = CommandPolicy(requiresSwitch = SwitchId("network.test")),
    ) {
    override suspend fun execute(params: Int): Outcome<CommandResult<Int>> = Outcome.Success(CommandResult(params))
}

internal class SelfCancellingCommand :
    Command<Int, Int>(
        id = CommandId("$TEST_PLUGIN_ID.cancel"),
        paramsSerializer = Int.serializer(),
        resultSerializer = Int.serializer(),
        doc = "Throws a cancellation from inside the command.",
    ) {
    override suspend fun execute(params: Int): Outcome<CommandResult<Int>> = throw CancellationException("command cancelled itself")
}

internal class ChainCommand :
    Command<Int, Int>(
        id = CommandId("$TEST_PLUGIN_ID.chain"),
        paramsSerializer = Int.serializer(),
        resultSerializer = Int.serializer(),
        doc = "Undoes through the nesting command.",
        policy = CommandPolicy(undoable = true),
    ) {
    override suspend fun execute(params: Int): Outcome<CommandResult<Int>> =
        Outcome.Success(
            CommandResult(
                value = params,
                inverse =
                    CommandInvocation(
                        commandId = CommandId("$TEST_PLUGIN_ID.nest"),
                        params = JsonPrimitive(params),
                    ),
            ),
        )
}

internal class NestingCommand(
    private val invoke: suspend (CommandId, JsonElement) -> Outcome<JsonElement>,
) : Command<Int, Int>(
        id = CommandId("$TEST_PLUGIN_ID.nest"),
        paramsSerializer = Int.serializer(),
        resultSerializer = Int.serializer(),
        doc = "Dispatches another command through the host.",
        policy = CommandPolicy(undoable = true),
    ) {
    override suspend fun execute(params: Int): Outcome<CommandResult<Int>> {
        val nested = invoke(CommandId("$TEST_PLUGIN_ID.set"), JsonPrimitive(params))
        if (nested is Outcome.Failure) {
            return nested
        }
        return Outcome.Success(CommandResult(params))
    }
}

internal class BlockingCommand(
    private val started: CompletableDeferred<Unit>,
    private val gate: CompletableDeferred<Unit>,
) : Command<Int, Int>(
        id = CommandId("$TEST_PLUGIN_ID.blocking"),
        paramsSerializer = Int.serializer(),
        resultSerializer = Int.serializer(),
        doc = "Blocks until cancelled or released.",
        policy = CommandPolicy(cancellableBy = CancellationKey("test.blocking")),
    ) {
    override suspend fun execute(params: Int): Outcome<CommandResult<Int>> {
        if (params == BLOCKING_PARAM) {
            started.complete(Unit)
            gate.await()
        }
        return Outcome.Success(CommandResult(params))
    }

    private companion object {
        const val BLOCKING_PARAM = 1
    }
}

internal class TestStream(
    val state: MutableStateFlow<Int> = MutableStateFlow(0),
) {
    val descriptor: StreamDescriptor<Int> =
        StreamDescriptor(
            id = STREAM_ID,
            serializer = Int.serializer(),
            flow = state,
        )

    companion object {
        val STREAM_ID = StreamId("$TEST_PLUGIN_ID.value")
        const val VALUE_TYPE = "kotlin.Int"
        const val SERIALIZER = "kotlinx.serialization.builtins.IntSerializer"
    }
}

internal fun testStreamDeclaration(): StreamDeclaration =
    StreamDeclaration(
        id = TestStream.STREAM_ID,
        valueType = TestStream.VALUE_TYPE,
        serializer = TestStream.SERIALIZER,
    )

internal fun testGeneratedStream(): GeneratedStream =
    GeneratedStream(
        id = TestStream.STREAM_ID,
        pluginId = PluginId(TEST_PLUGIN_ID),
        valueType = TestStream.VALUE_TYPE,
        serializer = TestStream.SERIALIZER,
    )

internal class ThrowingTestProvider : Provider {
    override val contract: ProviderContract =
        ProviderContract(
            name = "test.throwing",
            version = "1.0.0",
        )

    var calls: Int = 0
        private set

    fun fail(): Nothing {
        calls += 1
        throw IllegalStateException("test provider failure")
    }
}
