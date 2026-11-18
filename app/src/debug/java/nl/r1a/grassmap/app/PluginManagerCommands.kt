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
 * Debug-only commands that open and close the plugin manager (T-0607).
 *
 * The main UI has no entry point to the manager yet. They only toggle the
 * screen's visibility. Switching a plugin goes through `core.plugins.set_enabled`,
 * which is not allowlisted for control.
 */
internal val SHOW_PLUGINS_COMMAND: CommandId = CommandId("core.plugins.show")
internal val HIDE_PLUGINS_COMMAND: CommandId = CommandId("core.plugins.hide")

internal fun pluginManagerCommands(): List<Command<*, *>> = listOf(ShowPluginsCommand(), HidePluginsCommand())

/** Opens the plugin manager and reports that it is visible. */
internal class ShowPluginsCommand :
    Command<JsonElement, String>(
        id = SHOW_PLUGINS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Opens the plugin manager.",
        policy = CommandPolicy(allowlistedForControl = true, dispatcher = CommandDispatcher.IO),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        PluginManagerScreenSession.show()
        return Outcome.Success(CommandResult("visible=true"))
    }
}

/** Closes the plugin manager; it is safe when nothing is open. */
internal class HidePluginsCommand :
    Command<JsonElement, String>(
        id = HIDE_PLUGINS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Closes the plugin manager.",
        policy = CommandPolicy(allowlistedForControl = true, dispatcher = CommandDispatcher.IO),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        PluginManagerScreenSession.hide()
        return Outcome.Success(CommandResult("visible=false"))
    }
}
