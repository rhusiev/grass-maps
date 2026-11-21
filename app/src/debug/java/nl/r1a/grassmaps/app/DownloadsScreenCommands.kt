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

/**
 * Debug-only commands that open the downloads screen and allow region downloads
 * (T-0504).
 *
 * The main UI has no maps entry point yet; these are how the screen is reached
 * on a device until it gets one. They only toggle the shell visibility and the
 * privacy switch; every catalogue read and transfer still goes through the
 * `downloads.ui` commands and the store.
 */
internal val SHOW_DOWNLOADS_COMMAND: CommandId = CommandId("core.downloads.show")
internal val HIDE_DOWNLOADS_COMMAND: CommandId = CommandId("core.downloads.hide")
internal val REGION_DOWNLOADS_SWITCH_COMMAND: CommandId = CommandId("core.downloads.switch")

internal fun downloadsScreenCommands(container: () -> AppContainer?): List<Command<*, *>> =
    listOf(
        ShowDownloadsCommand(),
        HideDownloadsCommand(),
        RegionDownloadsSwitchCommand(container),
    )

/** Opens the downloads screen and reports that it is visible. */
internal class ShowDownloadsCommand :
    Command<JsonElement, String>(
        id = SHOW_DOWNLOADS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Opens the downloads screen.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        DownloadsScreenSession.show()
        return Outcome.Success(CommandResult("visible=true"))
    }
}

/** Closes the downloads screen; it is safe when nothing is open. */
internal class HideDownloadsCommand :
    Command<JsonElement, String>(
        id = HIDE_DOWNLOADS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Closes the downloads screen.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        DownloadsScreenSession.hide()
        return Outcome.Success(CommandResult("visible=false"))
    }
}

/** Turns the region-download privacy switch on or off. Parameter: enabled. */
internal class RegionDownloadsSwitchCommand(
    private val container: () -> AppContainer?,
) : Command<JsonElement, String>(
        id = REGION_DOWNLOADS_SWITCH_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Turns the region-download privacy switch on or off. Parameter: enabled.",
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
        return when (val outcome = app.setRegionDownloadsEnabled(enabled)) {
            is Outcome.Success -> Outcome.Success(CommandResult("enabled=$enabled"))
            is Outcome.Failure -> outcome
        }
    }

    private companion object {
        const val PARAM_ENABLED = "enabled"
    }
}
