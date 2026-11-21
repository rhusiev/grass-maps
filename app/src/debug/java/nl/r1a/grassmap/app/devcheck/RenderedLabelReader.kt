package nl.r1a.grassmaps.app.devcheck

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmaps.app.RENDERED_LABELS_COMMAND
import nl.r1a.grassmaps.plugin.api.error.Outcome

/** One symbol feature the map draws, as the rendered-label command reports it. */
internal data class LabelFeature(
    val layer: String,
    val text: String?,
    val names: Map<String, String>,
)

/**
 * Reads the labels the map draws back from the engine (T-0110). The command is
 * debug-only, so the check can take a real place name from the region this run
 * installed instead of a name written into the check.
 */
internal class RenderedLabelReader(
    private val context: CheckContext,
) {
    suspend fun features(): List<LabelFeature> {
        val result = context.command(RENDERED_LABELS_COMMAND.value, JsonObject(emptyMap()))
        val raw = ((result as? Outcome.Success)?.value as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        return parseLabels(raw)
    }
}

/** Reads the command's JSON array, dropping an entry that does not have the shape. */
internal fun parseLabels(raw: String): List<LabelFeature> {
    val entries = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
    return entries.mapNotNull { entry ->
        val fields = entry as? JsonObject ?: return@mapNotNull null
        LabelFeature(
            layer = (fields[LAYER_KEY] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            text = (fields[TEXT_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull,
            names = namesOf(fields[NAMES_KEY]),
        )
    }
}

/**
 * The place names in [features] to type into search, best first. A place layer
 * comes before the others, because a place is what the search scene shows; the
 * English name is used when the feature carries one, else the text the layer
 * draws.
 */
internal fun placeNames(features: List<LabelFeature>): List<String> =
    features
        .sortedBy { if (it.layer.isPlaceLayer()) 0 else 1 }
        .mapNotNull { it.names[ENGLISH_NAME_KEY] ?: it.text?.takeIf { text -> text.isNotBlank() } }
        .filter { it.isNotBlank() }
        .distinct()

private fun namesOf(names: JsonElement?): Map<String, String> {
    val fields = names as? JsonObject ?: return emptyMap()
    return fields
        .mapNotNull { (key, value) ->
            (value as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { key to it }
        }.toMap()
}

private fun String.isPlaceLayer(): Boolean = PLACE_LAYER_MARKERS.any { contains(it, ignoreCase = true) }

private const val LAYER_KEY = "layer"
private const val TEXT_KEY = "text"
private const val NAMES_KEY = "names"
private const val ENGLISH_NAME_KEY = "name:en"

private val PLACE_LAYER_MARKERS = listOf("place", "poi")
