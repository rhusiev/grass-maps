package nl.r1a.grassmaps.app

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import nl.r1a.grassmaps.plugin.api.command.Command
import nl.r1a.grassmaps.plugin.api.command.CommandDispatcher
import nl.r1a.grassmaps.plugin.api.command.CommandPolicy
import nl.r1a.grassmaps.plugin.api.command.CommandResult
import nl.r1a.grassmaps.plugin.api.contract.CommandId
import nl.r1a.grassmaps.plugin.api.error.Outcome

/**
 * Debug-only commands that hide and show the development-tools panel, so a
 * screenshot of the map or a sheet shows the product and not the harness.
 *
 * The panel is otherwise tied to the AI-control switch, which every debug
 * build holds on. These commands only toggle its visibility.
 */
internal val HIDE_DEVTOOLS_COMMAND: CommandId = CommandId("core.devtools.hide")
internal val SHOW_DEVTOOLS_COMMAND: CommandId = CommandId("core.devtools.show")

internal fun devToolsVisibilityCommands(container: () -> AppContainer): List<Command<*, *>> =
    listOf(
        DevToolsVisibilityCommand(HIDE_DEVTOOLS_COMMAND, container, visible = false),
        DevToolsVisibilityCommand(SHOW_DEVTOOLS_COMMAND, container, visible = true),
    )

/** Sets the panel's visibility and reports the new value. */
internal class DevToolsVisibilityCommand(
    id: CommandId,
    private val container: () -> AppContainer,
    private val visible: Boolean,
) : Command<JsonElement, String>(
        id = id,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Sets the development-tools panel's visibility. Result: visible=true or false.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        container().setDevToolsVisible(visible)
        return Outcome.Success(CommandResult("visible=$visible"))
    }
}
