package nl.r1a.grassmap.app

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import nl.r1a.grassmap.core.services.SearchHistorySettings
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome

/** The control-channel trigger for the search history switch (T-0207). */
internal val SEARCH_HISTORY_SETTING_COMMAND: CommandId = CommandId("core.settings.search_history")

/**
 * Turns the search history on or off.
 *
 * The settings screen arrives later; until then this command is how the control
 * channel proves the switch on a device. It takes `{"enabled":false}` or the
 * bare boolean, and it runs on IO because creating [SearchHistorySettings] may
 * read the settings file.
 */
internal class SearchHistorySettingCommand(
    private val settings: () -> SearchHistorySettings,
    private val log: (String) -> Unit = {},
) : Command<JsonElement, String>(
        id = SEARCH_HISTORY_SETTING_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Turns the search history on or off and returns the active value.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val requested = parse(params) ?: return Outcome.Failure(AppError.InvalidInput)
        return when (val outcome = settings().set(requested)) {
            is Outcome.Success -> {
                log("search history enabled=$requested")
                Outcome.Success(CommandResult("enabled=$requested"))
            }

            is Outcome.Failure -> {
                outcome
            }
        }
    }

    private fun parse(params: JsonElement): Boolean? =
        when (params) {
            is JsonPrimitive -> params.booleanOrNull
            is JsonObject -> (params[ENABLED_KEY] as? JsonPrimitive)?.booleanOrNull
            else -> null
        }

    private companion object {
        const val ENABLED_KEY = "enabled"
    }
}
