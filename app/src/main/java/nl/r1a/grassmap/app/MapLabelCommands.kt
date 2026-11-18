package nl.r1a.grassmap.app

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import nl.r1a.grassmap.map.LabelLanguage
import nl.r1a.grassmap.map.LabelOptions
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome

/** The control-channel trigger for the map label language and size (T-0110). */
internal val MAP_LABEL_SETTINGS_COMMAND: CommandId = CommandId("core.settings.map_labels")

/**
 * Sets the map label language and text size and returns the stored form.
 *
 * The settings screen arrives later; until then this command is how the control
 * channel proves the setting on a device. The parameter is an object with
 * `language` (`default`, `en` or `uk`) and `sizePercent` (50 to 200). A key left
 * out keeps its current value, so `{"sizePercent":150}` changes only the size.
 * The map redraws at once, with no restart.
 */
internal class MapLabelsCommand(
    private val settings: () -> MapLabelSettings,
    private val log: (String) -> Unit = {},
) : Command<JsonElement, String>(
        id = MAP_LABEL_SETTINGS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Sets the map label language (default, en, uk) and size (50 to 200 percent).",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val store = settings()
        val requested = parse(params, store.options.value) ?: return Outcome.Failure(AppError.InvalidInput)
        return when (val outcome = store.set(requested)) {
            is Outcome.Success -> {
                val stored = MapLabelSettings.encode(requested)
                log("map labels set to $stored")
                Outcome.Success(CommandResult(stored))
            }

            is Outcome.Failure -> {
                outcome
            }
        }
    }

    private fun parse(
        params: JsonElement,
        current: LabelOptions,
    ): LabelOptions? {
        val root = params as? JsonObject ?: return null
        val language = root[MapLabelSettings.LANGUAGE_KEY]?.let { LabelLanguage.fromWireName(it.text()) ?: return null }
        val size = root[MapLabelSettings.SIZE_KEY]?.let { (it as? JsonPrimitive)?.intOrNull ?: return null }
        return LabelOptions(
            language = language ?: current.language,
            sizePercent = size ?: current.sizePercent,
        )
    }

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.contentOrNull
}
