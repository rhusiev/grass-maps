package nl.r1a.grassmap.map.style

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import nl.r1a.grassmap.map.LabelLanguage
import nl.r1a.grassmap.map.LabelOptions
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LayerType

/**
 * Applies the label options to a symbol layer (T-0110). The style files stay
 * as authored. The options default leaves every layer untouched, so the
 * default render is byte-for-byte what the style produces.
 */
internal object LabelStyle {
    private const val TEXT_FIELD = "text-field"
    private const val TEXT_SIZE = "text-size"
    private const val AUTHORED_LANGUAGE_KEY = "name:uk"
    private const val LOCAL_PSEUDO_NAME_KEY = "pgf:name"
    private const val LOCAL_NAME_KEY = "name"
    private val NAME_RULE_OPERATORS = setOf("case", "format")
    private val ZOOM_CURVE_OPERATORS = setOf("interpolate", "interpolate-hcl", "interpolate-lab")
    private const val STEP_OPERATOR = "step"
    private const val CASE_OPERATOR = "case"
    private const val MULTIPLY_OPERATOR = "*"
    private const val INTERPOLATE_FIRST_OUTPUT = 4
    private const val STEP_FIRST_OUTPUT = 2
    private const val CASE_FIRST_OUTPUT = 2
    private const val OUTPUT_STRIDE = 2

    fun apply(
        spec: LayerSpec,
        options: LabelOptions,
    ): LayerSpec {
        if (spec.type != LayerType.Symbol || options.isDefault) {
            return spec
        }
        val layout = LinkedHashMap(spec.layout)
        val nameKey = options.language.nameKey
        val field = layout[TEXT_FIELD]
        if (nameKey != null && field != null) {
            layout[TEXT_FIELD] = withLanguage(field, nameKey)
        }
        val size = layout[TEXT_SIZE]
        if (size != null && options.sizePercent != LabelOptions.DEFAULT_SIZE_PERCENT) {
            layout[TEXT_SIZE] = scaled(size, options.sizeFactor)
        }
        return spec.copy(layout = JsonObject(layout))
    }

    /**
     * Replaces the style's name rule, the outermost `case` or `format` that
     * reads the authored language tag, with one name in [nameKey]'s language.
     * A rule around it, such as the region layer's reference number below
     * zoom 6, stays.
     */
    internal fun withLanguage(
        field: JsonElement,
        nameKey: String,
    ): JsonElement {
        if (field !is JsonArray) {
            return field
        }
        val operator = (field.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (operator in NAME_RULE_OPERATORS && readsAuthoredLanguage(field)) {
            return singleName(nameKey)
        }
        return JsonArray(field.map { withLanguage(it, nameKey) })
    }

    private fun singleName(nameKey: String): JsonArray =
        JsonArray(
            listOf(
                JsonPrimitive("coalesce"),
                getTag(nameKey),
                getTag(LOCAL_PSEUDO_NAME_KEY),
                getTag(LOCAL_NAME_KEY),
            ),
        )

    private fun getTag(key: String): JsonArray = JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive(key)))

    private fun readsAuthoredLanguage(element: JsonElement): Boolean =
        when (element) {
            is JsonArray -> element.any { readsAuthoredLanguage(it) }
            is JsonPrimitive -> element.isString && element.content == AUTHORED_LANGUAGE_KEY
            is JsonObject -> false
        }

    /**
     * Multiplies a text size by [factor]. A zoom curve takes the factor on its
     * outputs, because MapLibre only accepts the zoom input at the top of a
     * `step` or `interpolate`.
     */
    internal fun scaled(
        size: JsonElement,
        factor: Double,
    ): JsonElement {
        if (size is JsonPrimitive) {
            val number = size.doubleOrNull
            return if (number != null) JsonPrimitive(number * factor) else size
        }
        val array = size as? JsonArray ?: return size
        val operator = (array.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when {
            operator in ZOOM_CURVE_OPERATORS -> scaledAt(array, factor, INTERPOLATE_FIRST_OUTPUT)
            operator == STEP_OPERATOR -> scaledAt(array, factor, STEP_FIRST_OUTPUT)
            operator == CASE_OPERATOR -> scaledCase(array, factor)
            else -> product(factor, array)
        }
    }

    private fun scaledAt(
        array: JsonArray,
        factor: Double,
        firstOutput: Int,
    ): JsonArray =
        JsonArray(
            array.mapIndexed { index, element ->
                val isOutput = index >= firstOutput && (index - firstOutput) % OUTPUT_STRIDE == 0
                if (isOutput) scaled(element, factor) else element
            },
        )

    private fun scaledCase(
        array: JsonArray,
        factor: Double,
    ): JsonArray {
        val fallbackIndex = array.size - 1
        return JsonArray(
            array.mapIndexed { index, element ->
                val isBranch = index == fallbackIndex || (index >= CASE_FIRST_OUTPUT && index % OUTPUT_STRIDE == 0)
                if (isBranch) scaled(element, factor) else element
            },
        )
    }

    private fun product(
        factor: Double,
        element: JsonElement,
    ): JsonArray = JsonArray(listOf(JsonPrimitive(MULTIPLY_OPERATOR), JsonPrimitive(factor), element))
}
