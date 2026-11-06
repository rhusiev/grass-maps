package nl.r1a.grassmap.plugin.api.model

import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.provider.ProviderContract

enum class PluginState {
    DECLARED,
    ACTIVATING,
    RUNNING,
    FAILED,
    DEACTIVATED,
}

data class PluginStatus(
    val pluginId: PluginId,
    val state: PluginState,
    val failures: Int = 0,
    val activatedAtMillis: Long? = null,
    val activationMillis: Long? = null,
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
