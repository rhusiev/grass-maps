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
 * Debug-only commands that open and close the lists sheet (T-0213).
 *
 * The main UI has no favourites entry point yet; these are how the sheet is
 * reached on a device until T-0214/T-0215 add one. They only toggle the shell
 * visibility; every mutation still goes through the `favourites.ui` commands.
 */
internal val SHOW_LISTS_COMMAND: CommandId = CommandId("core.favourites.lists_show")
internal val HIDE_LISTS_COMMAND: CommandId = CommandId("core.favourites.lists_hide")

internal fun favouritesListsCommands(): List<Command<*, *>> = listOf(ShowListsCommand(), HideListsCommand())

/** Opens the lists sheet and reports that it is visible. */
internal class ShowListsCommand :
    Command<JsonElement, String>(
        id = SHOW_LISTS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Opens the favourites lists sheet.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        FavouritesListsSession.show()
        return Outcome.Success(CommandResult("visible=true"))
    }
}

/** Closes the lists sheet; it is safe when nothing is open. */
internal class HideListsCommand :
    Command<JsonElement, String>(
        id = HIDE_LISTS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Closes the favourites lists sheet.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        FavouritesListsSession.hide()
        return Outcome.Success(CommandResult("visible=false"))
    }
}
