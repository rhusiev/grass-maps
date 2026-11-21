package nl.r1a.grassmaps.plugin.api.model

import kotlinx.serialization.Serializable
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.manifest.LocationNeed
import nl.r1a.grassmaps.plugin.api.provider.ProviderContract

enum class PluginState {
    DECLARED,
    ACTIVATING,
    RUNNING,
    FAILED,
    DEACTIVATED,
}

/**
 * What the plugin manager draws about one plugin: the manifest's text keys and
 * needs, copied at construction so the manager never reads a manifest.
 */
@Serializable
data class PluginInfo(
    val nameKey: String,
    val descriptionKey: String,
    val version: String,
    val commandCount: Int,
    val network: List<String> = emptyList(),
    val location: LocationNeed = LocationNeed.NONE,
    val regions: List<String> = emptyList(),
)

/**
 * One plugin's lifecycle and user switch. [enabled] is the user's choice and
 * persists. [switchable] is false for a plugin the app cannot run without, so
 * the manager does not offer a switch for it.
 */
@Serializable
data class PluginStatus(
    val pluginId: PluginId,
    val state: PluginState,
    val failures: Int = 0,
    val activatedAtMillis: Long? = null,
    val activationMillis: Long? = null,
    val enabled: Boolean = true,
    val switchable: Boolean = false,
    val info: PluginInfo? = null,
)

enum class ProviderState {
    ACTIVE,
    DISABLED,
}

data class ProviderStatus(
    val pluginId: PluginId,
    val contract: ProviderContract,
    val state: ProviderState,
    val failures: Int = 0,
)
