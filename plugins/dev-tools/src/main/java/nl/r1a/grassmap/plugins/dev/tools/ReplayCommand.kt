package nl.r1a.grassmap.plugins.dev.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonElement
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.DevToolsAccess

/**
 * Shared plumbing for the two replay commands: run the host action, then
 * publish the resulting panel state so every subscriber sees the change
 * immediately instead of waiting for the next poll.
 */
abstract class ReplayCommand(
    id: CommandId,
    doc: String,
    protected val devTools: DevToolsAccess,
    protected val state: MutableStateFlow<DevToolsPanelState>,
) : Command<JsonElement, DevToolsPanelState>(
        id = id,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = DevToolsPanelState.serializer(),
        doc = doc,
        policy =
            CommandPolicy(
                requiresSwitch = DevToolsContract.aiControlSwitch,
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    protected fun publish(outcome: Outcome<Unit>): Outcome<CommandResult<DevToolsPanelState>> =
        when (outcome) {
            is Outcome.Success -> {
                val next = devTools.panelState()
                state.value = next
                Outcome.Success(CommandResult(next))
            }

            is Outcome.Failure -> {
                outcome
            }
        }
}

class StartReplayCommand(
    devTools: DevToolsAccess,
    state: MutableStateFlow<DevToolsPanelState>,
) : ReplayCommand(
        id = DevToolsContract.startReplayCommand,
        doc = "Starts the GPX replay and returns the panel state.",
        devTools = devTools,
        state = state,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<DevToolsPanelState>> = publish(devTools.startReplay())
}

class StopReplayCommand(
    devTools: DevToolsAccess,
    state: MutableStateFlow<DevToolsPanelState>,
) : ReplayCommand(
        id = DevToolsContract.stopReplayCommand,
        doc = "Stops the GPX replay and returns the panel state.",
        devTools = devTools,
        state = state,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<DevToolsPanelState>> {
        devTools.stopReplay()
        return publish(Outcome.Success(Unit))
    }
}
