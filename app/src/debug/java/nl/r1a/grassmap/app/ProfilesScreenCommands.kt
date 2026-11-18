package nl.r1a.grassmap.app

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * Debug-only commands that open and close the profiles screen (T-0602).
 *
 * The main UI has no profiles entry point yet. These toggle the shell
 * visibility only. Every profile change still goes through the `profiles.ui`
 * commands.
 */
internal val SHOW_PROFILES_COMMAND: CommandId = CommandId("core.profiles.show")
internal val HIDE_PROFILES_COMMAND: CommandId = CommandId("core.profiles.hide")

internal fun profilesScreenCommands(): List<Command<*, *>> = listOf(ShowProfilesCommand(), HideProfilesCommand())

/** Opens the profiles screen and reports that it is visible. */
internal class ShowProfilesCommand :
    Command<JsonElement, String>(
        id = SHOW_PROFILES_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Opens the profiles screen.",
        policy = CommandPolicy(allowlistedForControl = true, dispatcher = CommandDispatcher.IO),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        ProfilesScreenSession.show()
        return Outcome.Success(CommandResult("visible=true"))
    }
}

/** Closes the profiles screen; it is safe when nothing is open. */
internal class HideProfilesCommand :
    Command<JsonElement, String>(
        id = HIDE_PROFILES_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Closes the profiles screen.",
        policy = CommandPolicy(allowlistedForControl = true, dispatcher = CommandDispatcher.IO),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        ProfilesScreenSession.hide()
        return Outcome.Success(CommandResult("visible=false"))
    }
}
