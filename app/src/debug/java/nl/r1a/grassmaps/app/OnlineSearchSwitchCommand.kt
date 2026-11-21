package nl.r1a.grassmaps.app

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import nl.r1a.grassmaps.plugin.api.command.Command
import nl.r1a.grassmaps.plugin.api.command.CommandDispatcher
import nl.r1a.grassmaps.plugin.api.command.CommandPolicy
import nl.r1a.grassmaps.plugin.api.command.CommandResult
import nl.r1a.grassmaps.plugin.api.contract.CommandId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome

/** The debug-only control path to the online-search privacy switch (T-0616). */
internal val ONLINE_SEARCH_SWITCH_COMMAND: CommandId = CommandId("core.search.switch")

/**
 * Turns `network.online_search` on or off. The user's path is the online notice
 * on the search screen; this command lets a test or Device check reach the same
 * switch. It only changes the switch, so a request still goes through the gate.
 */
internal class OnlineSearchSwitchCommand(
    private val container: () -> AppContainer?,
) : Command<JsonElement, String>(
        id = ONLINE_SEARCH_SWITCH_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Turns the online-search privacy switch on or off. Parameter: enabled.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val enabled =
            ((params as? JsonObject)?.get(PARAM_ENABLED) as? JsonPrimitive)?.booleanOrNull
                ?: return Outcome.Failure(AppError.InvalidInput)
        val app = container() ?: return Outcome.Failure(AppError.NotReady)
        return when (val outcome = app.setOnlineSearchEnabled(enabled)) {
            is Outcome.Success -> Outcome.Success(CommandResult("enabled=$enabled"))
            is Outcome.Failure -> outcome
        }
    }

    private companion object {
        const val PARAM_ENABLED = "enabled"
    }
}
