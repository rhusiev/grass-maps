package nl.r1a.grassmaps.app

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmaps.map.MaplibreComposeMapHost
import nl.r1a.grassmaps.plugin.api.command.Command
import nl.r1a.grassmaps.plugin.api.command.CommandDispatcher
import nl.r1a.grassmaps.plugin.api.command.CommandPolicy
import nl.r1a.grassmaps.plugin.api.command.CommandResult
import nl.r1a.grassmaps.plugin.api.contract.CommandId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome

/** The debug-only command that reads back the labels the map draws (T-0110). */
internal val RENDERED_LABELS_COMMAND: CommandId = CommandId("core.map.rendered_labels")

private const val NAME_EN_PARAM = "nameEn"

/**
 * Queries the rendered symbol features and returns, per feature, its names and
 * the text its layer draws, as the engine evaluates the layer's `text-field`.
 * It exists only in the debug source set: the release app contributes no such
 * command.
 */
internal class RenderedLabelsCommand(
    private val session: MapSessionHolder,
) : Command<JsonElement, String>(
        id = RENDERED_LABELS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Returns the rendered symbol features with the text each draws. Optional nameEn limits it.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val nameEn = (params as? JsonObject)?.get(NAME_EN_PARAM)?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        val host = session.hostOrNull() as? MaplibreComposeMapHost ?: return Outcome.Failure(AppError.NotReady)
        val labels = host.renderedLabels(nameEn)
        val json =
            buildJsonArray {
                for (label in labels) {
                    add(
                        buildJsonObject {
                            put("layer", label.layer)
                            put("text", label.text)
                            put("names", JsonObject(label.names.mapValues { JsonPrimitive(it.value) }))
                            put("textSize", label.textSize ?: JsonNull)
                        },
                    )
                }
            }
        return Outcome.Success(CommandResult(json.toString()))
    }
}
