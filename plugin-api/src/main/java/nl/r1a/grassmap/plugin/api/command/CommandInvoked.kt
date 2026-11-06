package nl.r1a.grassmap.plugin.api.command

import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError

/**
 * Emitted for every command dispatch, including failed and cancelled ones.
 */
data class CommandInvoked(
    val commandId: CommandId,
    val pluginId: PluginId?,
    val succeeded: Boolean,
    val error: AppError? = null,
    val durationMillis: Long,
)
