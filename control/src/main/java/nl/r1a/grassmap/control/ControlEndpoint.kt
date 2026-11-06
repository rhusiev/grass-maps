package nl.r1a.grassmap.control

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.CommandInvoker
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.manifest.GeneratedCommand
import nl.r1a.grassmap.plugin.api.manifest.PluginManifest

/** One command as the control channel lists and gates it. */
data class ControlCommand(
    val id: CommandId,
    val doc: String,
    val plugin: PluginId,
    val allowlisted: Boolean,
    val requiresSwitch: SwitchId?,
    val dispatcher: CommandDispatcher,
)

/** The static command view: listing and gating never activate a plugin. */
interface ControlCatalog {
    fun commands(): List<ControlCommand>

    fun command(id: String): ControlCommand?
}

/**
 * Builds the catalog from the generated registry (id, plugin, policy) and the
 * plugin manifests (doc). Both are build-time data, so listing a command costs
 * no plugin activation.
 */
class GeneratedControlCatalog(
    commands: List<GeneratedCommand>,
    manifests: List<PluginManifest>,
) : ControlCatalog {
    private val docs: Map<CommandId, String> =
        manifests
            .flatMap { manifest -> manifest.contributes.commands.map { it.id to it.doc } }
            .toMap()
    private val byId: Map<String, ControlCommand> =
        commands.associate { generated ->
            generated.id.value to
                ControlCommand(
                    id = generated.id,
                    doc = docs[generated.id].orEmpty(),
                    plugin = generated.pluginId,
                    allowlisted = generated.allowlistedForControl,
                    requiresSwitch = generated.requiresSwitch,
                    dispatcher = generated.dispatcher,
                )
        }

    override fun commands(): List<ControlCommand> = byId.values.sortedBy { it.id.value }

    override fun command(id: String): ControlCommand? = byId[id]
}

/** A stream opened for the control channel, already encoded as JSON values. */
fun interface ControlStreamSource {
    suspend fun open(id: StreamId): Outcome<Flow<JsonElement>>
}

/**
 * Opens the declared stream for its owner plugin. Activation is the collection
 * of the stream, exactly as for a UI subscriber.
 */
class PluginHostControlStreams(
    private val host: PluginHost,
) : ControlStreamSource {
    override suspend fun open(id: StreamId): Outcome<Flow<JsonElement>> =
        when (val stream = host.stream(id)) {
            is Outcome.Success -> Outcome.Success(encoded(stream.value))
            is Outcome.Failure -> stream
        }

    @Suppress("UNCHECKED_CAST")
    private fun encoded(descriptor: StreamDescriptor<*>): Flow<JsonElement> =
        flow {
            val serializer = descriptor.serializer as KSerializer<Any>
            val values = descriptor.flow as Flow<Any>
            values.collect { value ->
                emit(json.encodeToJsonElement(serializer, value))
            }
        }

    private companion object {
        val json: Json = Json { encodeDefaults = true }
    }
}

/** PNG bytes and the capture path that produced them, for evidence. */
class ControlCapture(
    val png: ByteArray,
    val source: String,
)

/** The window capture behind the `devtools.screenshot` method. */
fun interface ControlScreenshot {
    suspend fun capturePng(): Outcome<ControlCapture>
}

/**
 * Everything the channel may use. The caller wires implementations; the channel
 * itself holds no Android type.
 */
class ControlEndpoint(
    val catalog: ControlCatalog,
    val invoker: CommandInvoker,
    val streams: ControlStreamSource,
    val screenshot: ControlScreenshot? = null,
)
