package nl.r1a.grassmaps.app

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 * The debug command behind the two-theme screenshots (T-0612). It pins the
 * shell theme instead of changing the emulator's night mode, so a light and a
 * dark capture run in the same process and cannot race the system setting.
 *
 * Parameter: `dark` is true or false to pin the theme, or null to follow the
 * system again.
 */
internal val SET_THEME_COMMAND: CommandId = CommandId("core.theme.set")

internal fun themeCommands(container: () -> AppContainer): List<Command<*, *>> = listOf(SetThemeCommand(container))

internal class SetThemeCommand(
    private val container: () -> AppContainer,
) : Command<JsonElement, String>(
        id = SET_THEME_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Pins the shell theme. Parameter: dark (true, false, or null for the system theme).",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val json = params as? JsonObject ?: return Outcome.Failure(AppError.InvalidInput)
        val dark =
            when (val value = json[PARAM_DARK]) {
                null, JsonNull -> null
                is JsonPrimitive -> value.booleanOrNull ?: return Outcome.Failure(AppError.InvalidInput)
                else -> return Outcome.Failure(AppError.InvalidInput)
            }
        container().setDarkTheme(dark)
        return Outcome.Success(CommandResult("dark=${dark ?: "system"}"))
    }

    private companion object {
        const val PARAM_DARK = "dark"
    }
}
