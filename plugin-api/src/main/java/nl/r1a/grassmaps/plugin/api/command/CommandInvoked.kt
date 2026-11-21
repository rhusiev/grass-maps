package nl.r1a.grassmaps.plugin.api.command

import nl.r1a.grassmaps.plugin.api.contract.CommandId
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.error.AppError

/**
 * Emitted on a best-effort basis for every command dispatch, including failed and
 * cancelled ones. The flow has no replay and drops events when nothing collects,
 * so it is not a guaranteed audit log.
 */
data class CommandInvoked(
    val commandId: CommandId,
    val pluginId: PluginId?,
    val succeeded: Boolean,
    val error: AppError? = null,
    val durationMillis: Long,
)
