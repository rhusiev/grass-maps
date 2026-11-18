package nl.r1a.grassmap.core

import kotlinx.serialization.builtins.ListSerializer
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.PluginManagement
import nl.r1a.grassmap.plugin.api.contract.SetEnabledParams
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.PluginStatus

/**
 * `core.plugins.set_enabled` (T-0607): turns one plugin on or off and returns
 * its new status. It changes what the app runs, so it is not allowlisted for the
 * control channel, like the map controls.
 */
class SetPluginEnabledCommand(
    private val host: () -> PluginHost,
) : Command<SetEnabledParams, PluginStatus>(
        id = PluginManagement.setEnabled,
        paramsSerializer = SetEnabledParams.serializer(),
        resultSerializer = PluginStatus.serializer(),
        doc = "Turns a plugin on or off and returns its status. Parameters: plugin, enabled.",
        policy = CommandPolicy(dispatcher = CommandDispatcher.IO),
    ) {
    override suspend fun execute(params: SetEnabledParams): Outcome<CommandResult<PluginStatus>> =
        when (val outcome = host().setEnabled(params.plugin, params.enabled)) {
            is Outcome.Success -> Outcome.Success(CommandResult(outcome.value))
            is Outcome.Failure -> outcome
        }
}

/** The stream serializer of `core.plugins.state`. */
internal val pluginStateSerializer = ListSerializer(PluginStatus.serializer())
