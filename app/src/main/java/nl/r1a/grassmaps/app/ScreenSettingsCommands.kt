package nl.r1a.grassmaps.app

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmaps.core.services.ScreenSettings
import nl.r1a.grassmaps.plugin.api.command.Command
import nl.r1a.grassmaps.plugin.api.command.CommandDispatcher
import nl.r1a.grassmaps.plugin.api.command.CommandPolicy
import nl.r1a.grassmaps.plugin.api.command.CommandResult
import nl.r1a.grassmaps.plugin.api.contract.CommandId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome

/** The control-channel path to one of the six screen rows (T-0111). */
internal val SCREEN_SETTINGS_COMMAND: CommandId = CommandId("core.settings.screen")

/**
 * Stores one row of the screen settings and returns the whole stored row set.
 * It takes `{"key":"app_theme","value":"dark"}`, with the same wire values the
 * settings screen writes. It runs on IO because the first access reads the
 * per-profile file.
 */
internal class ScreenSettingsCommand(
    private val settings: () -> ScreenSettings,
) : Command<JsonElement, String>(
        id = SCREEN_SETTINGS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Sets one screen setting by key and value. Keys: app_theme, night_map, keep_screen_on, animations, language.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val objectParams = params as? JsonObject ?: return Outcome.Failure(AppError.InvalidInput)
        val key = (objectParams[KEY] as? JsonPrimitive)?.contentOrNull ?: return Outcome.Failure(AppError.InvalidInput)
        val value = (objectParams[VALUE] as? JsonPrimitive)?.contentOrNull ?: return Outcome.Failure(AppError.InvalidInput)
        return when (val written = settings().set(key, value)) {
            is Outcome.Failure -> written
            is Outcome.Success -> Outcome.Success(CommandResult(settings().current().asStoredValues().toString()))
        }
    }

    private companion object {
        const val KEY = "key"
        const val VALUE = "value"
    }
}
