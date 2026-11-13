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
 * Debug-only commands that open and close the track library screen (T-0414).
 *
 * The main UI has no track library entry point yet; these are how the screen is
 * reached on a device until the map's track selection opens it (T-0415). They
 * only toggle the shell visibility; every mutation still goes through the
 * `track.library` commands.
 */
internal val SHOW_TRACK_LIBRARY_COMMAND: CommandId = CommandId("core.track.library_show")
internal val HIDE_TRACK_LIBRARY_COMMAND: CommandId = CommandId("core.track.library_hide")

internal fun trackLibraryCommands(): List<Command<*, *>> = listOf(ShowTrackLibraryCommand(), HideTrackLibraryCommand())

/** Opens the track library screen and reports that it is visible. */
internal class ShowTrackLibraryCommand :
    Command<JsonElement, String>(
        id = SHOW_TRACK_LIBRARY_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Opens the track library screen.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        TrackLibraryScreen.show()
        return Outcome.Success(CommandResult("visible=true"))
    }
}

/** Closes the track library screen; it is safe when nothing is open. */
internal class HideTrackLibraryCommand :
    Command<JsonElement, String>(
        id = HIDE_TRACK_LIBRARY_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Closes the track library screen.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        TrackLibraryScreen.hide()
        return Outcome.Success(CommandResult("visible=false"))
    }
}
