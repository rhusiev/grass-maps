package nl.r1a.grassmaps.app

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import nl.r1a.grassmaps.plugin.api.command.Command
import nl.r1a.grassmaps.plugin.api.command.CommandDispatcher
import nl.r1a.grassmaps.plugin.api.command.CommandPolicy
import nl.r1a.grassmaps.plugin.api.command.CommandResult
import nl.r1a.grassmaps.plugin.api.contract.CommandId
import nl.r1a.grassmaps.plugin.api.error.Outcome

/** Debug-only commands that open and close the settings screen (T-0111). */
internal val SHOW_SCREEN_SETTINGS_COMMAND: CommandId = CommandId("core.settings.show")
internal val HIDE_SCREEN_SETTINGS_COMMAND: CommandId = CommandId("core.settings.hide")

internal fun screenSettingsScreenCommands(): List<Command<*, *>> = listOf(ShowScreenSettingsCommand(), HideScreenSettingsCommand())

internal class ShowScreenSettingsCommand :
    Command<JsonElement, String>(
        id = SHOW_SCREEN_SETTINGS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Opens the theme and screen settings page.",
        policy = CommandPolicy(allowlistedForControl = true, dispatcher = CommandDispatcher.IO),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        ScreenSettingsSession.show()
        return Outcome.Success(CommandResult("visible=true"))
    }
}

internal class HideScreenSettingsCommand :
    Command<JsonElement, String>(
        id = HIDE_SCREEN_SETTINGS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Closes the theme and screen settings page.",
        policy = CommandPolicy(allowlistedForControl = true, dispatcher = CommandDispatcher.IO),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        ScreenSettingsSession.hide()
        return Outcome.Success(CommandResult("visible=false"))
    }
}
