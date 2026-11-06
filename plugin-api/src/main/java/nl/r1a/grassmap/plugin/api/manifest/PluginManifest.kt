package nl.r1a.grassmap.plugin.api.manifest

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.provider.ProviderContract

/**
 * The build-time declaration of a compiled-in plugin. The Gradle generator reads
 * `src/main/plugin.json` and emits this model into the generated registry.
 */
data class PluginManifest(
    val id: PluginId,
    val name: String,
    val description: String,
    val version: String,
    val api: String,
    val implementation: String,
    val contributes: PluginContributions = PluginContributions(),
    val needs: PluginNeeds = PluginNeeds(),
    val activation: List<String> = emptyList(),
)

data class PluginContributions(
    val commands: List<CommandDeclaration> = emptyList(),
    val providers: List<ProviderDeclaration> = emptyList(),
    val streams: List<StreamDeclaration> = emptyList(),
    val settings: List<SettingDeclaration> = emptyList(),
)

data class CommandDeclaration(
    val id: CommandId,
    val params: String,
    val result: String,
    val doc: String,
    val undoable: Boolean = false,
    val requiresSwitch: SwitchId? = null,
    val allowlistedForControl: Boolean = false,
    val dispatcher: CommandDispatcher = CommandDispatcher.DEFAULT,
    val implementation: String,
)

data class ProviderDeclaration(
    val contract: String,
    val version: String,
    val capabilities: JsonObject = JsonObject(emptyMap()),
)

data class StreamDeclaration(
    val id: StreamId,
    val valueType: String,
    val serializer: String,
)

data class SettingDeclaration(
    val key: String,
    val type: String,
    val default: JsonElement = JsonNull,
    val scope: SettingScope,
)

enum class SettingScope {
    GLOBAL,
    PROFILE,
}

data class PluginNeeds(
    val network: List<String> = emptyList(),
    val location: LocationNeed = LocationNeed.NONE,
    val regions: List<String> = emptyList(),
)

enum class LocationNeed {
    NONE,
    WHILE_IN_USE,
    DURING_NAVIGATION,
}

/**
 * Binds a manifest to the code that creates the plugin. Generated at build time;
 * the host never scans or reflects.
 */
class PluginFactory(
    val manifest: PluginManifest,
    val create: () -> Plugin,
)

data class GeneratedCommand(
    val id: CommandId,
    val pluginId: PluginId,
    val undoable: Boolean = false,
    val requiresSwitch: SwitchId? = null,
    val allowlistedForControl: Boolean = false,
    val dispatcher: CommandDispatcher = CommandDispatcher.DEFAULT,
)

data class GeneratedProvider(
    val pluginId: PluginId,
    val contract: ProviderContract,
)

data class GeneratedStream(
    val id: StreamId,
    val pluginId: PluginId,
    val valueType: String,
    val serializer: String,
)
