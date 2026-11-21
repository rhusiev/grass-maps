package nl.r1a.grassmaps.map

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmaps.map.style.LabelStyle
import nl.r1a.grassmaps.map.style.StyleExpression
import nl.r1a.grassmaps.plugin.api.model.LayerSpec
import nl.r1a.grassmaps.plugin.api.model.LayerType
import org.maplibre.compose.map.MapState

/** One rendered symbol feature, and the text its layer draws for it. */
data class RenderedLabel(
    val layer: String,
    val names: Map<String, String>,
    val text: String?,
    val textSize: JsonElement?,
)

/**
 * Reads back what the engine draws for symbol features, as a diagnostic for
 * the debug build (T-0110). The text is the layer's own `text-field`, evaluated
 * by the engine: the query is repeated with a filter that compares that
 * expression to each name the feature carries, so the answer does not come from
 * this code's reading of the expression.
 */
internal class RenderedLabels(
    private val state: MapState,
) {
    suspend fun query(
        layers: List<LayerSpec>,
        options: LabelOptions,
        nameEn: String?,
    ): List<RenderedLabel> {
        val viewport = state.viewport?.size ?: return emptyList()
        val area = DpRect(0.dp, 0.dp, viewport.width, viewport.height)
        return layers
            .map { LabelStyle.apply(it, options) }
            .filter { it.type == LayerType.Symbol && it.layout[TEXT_FIELD] != null }
            .flatMap { layer -> labelsOf(layer, area, nameEn) }
    }

    private suspend fun labelsOf(
        layer: LayerSpec,
        area: DpRect,
        nameEn: String?,
    ): List<RenderedLabel> {
        val id = setOf(layer.id.value)
        val field = layer.layout.getValue(TEXT_FIELD)
        return state
            .queryRenderedFeatures(area, id)
            .mapNotNull { it.properties }
            .filter { nameEn == null || it[NAME_EN_KEY]?.contentOrNull() == nameEn }
            .distinct()
            .map { properties ->
                val names = namesOf(properties)
                val text = names.values.distinct().firstOrNull { drawsText(id, area, field, it, properties) }
                RenderedLabel(layer.id.value, names, text, layer.layout[TEXT_SIZE])
            }
    }

    private suspend fun drawsText(
        layerIds: Set<String>,
        area: DpRect,
        field: JsonElement,
        candidate: String,
        properties: JsonObject,
    ): Boolean {
        val equalsCandidate =
            JsonArray(
                listOf(
                    JsonPrimitive("=="),
                    JsonArray(listOf(JsonPrimitive("to-string"), field)),
                    JsonPrimitive(candidate),
                ),
            )
        return state
            .queryRenderedFeatures(area, layerIds, StyleExpression.filter(equalsCandidate))
            .any { it.properties == properties }
    }

    private fun namesOf(properties: JsonObject): Map<String, String> =
        properties
            .mapNotNull { (key, value) -> value.contentOrNull()?.takeIf { key.isNameKey() }?.let { key to it } }
            .toMap()

    private fun String.isNameKey(): Boolean = this == LOCAL_NAME_KEY || startsWith("$LOCAL_NAME_KEY:") || this == PSEUDO_NAME_KEY

    private fun JsonElement.contentOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val TEXT_FIELD = "text-field"
        const val TEXT_SIZE = "text-size"
        const val NAME_EN_KEY = "name:en"
        const val LOCAL_NAME_KEY = "name"
        const val PSEUDO_NAME_KEY = "pgf:name"
    }
}
