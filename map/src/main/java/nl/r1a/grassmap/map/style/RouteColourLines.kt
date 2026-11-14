package nl.r1a.grassmap.map.style

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import nl.r1a.grassmap.plugin.api.model.RouteLinePattern
import nl.r1a.grassmap.plugin.api.model.RouteLineStyle
import nl.r1a.grassmap.plugin.api.provider.Route
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.NumberValue
import org.maplibre.compose.expressions.value.VectorValue

/**
 * The route layer's coloured line as map data (T-0408).
 *
 * One GeoJSON FeatureCollection holds every run as its own LineString, with the
 * colour and the pattern as feature properties. Three style layers draw it: the
 * solid line, the dashes and the steps' stripe ink. The layer count is therefore
 * fixed at three whatever the number of runs, and the data is built once per
 * route or style change rather than on a camera move.
 *
 * The host validates the runs here: a run is clipped to the geometry, a run
 * with fewer than two points is dropped, and a malformed colour is not parsed.
 * The route layer builds a complete partition, so the clipping is a guard, not
 * the rule.
 */
internal object RouteColourLines {
    /** Properties the three layers filter on and read. */
    const val COLOUR_PROPERTY = "colour"
    const val PATTERN_PROPERTY = "pattern"

    const val SOLID_PATTERN = "solid"
    const val DASHED_PATTERN = "dashed"
    const val STRIPED_PATTERN = "striped"

    fun featureCollection(
        route: Route,
        style: RouteLineStyle,
    ): String {
        val features =
            JsonArray(
                style.runs.mapNotNull { run ->
                    val start = run.startIndex.coerceIn(0, route.geometry.lastIndex)
                    val end = run.endIndex.coerceIn(0, route.geometry.lastIndex)
                    if (end - start < MIN_RUN_POINTS - 1) {
                        return@mapNotNull null
                    }
                    buildJsonObject {
                        put("type", "Feature")
                        putJsonObject("properties") {
                            put(COLOUR_PROPERTY, run.colour)
                            put(PATTERN_PROPERTY, patternName(run.pattern))
                        }
                        putJsonObject("geometry") {
                            put("type", "LineString")
                            putJsonArray("coordinates") {
                                for (point in route.geometry.subList(start, end + 1)) {
                                    addJsonArray {
                                        add(point.longitude)
                                        add(point.latitude)
                                    }
                                }
                            }
                        }
                    }
                },
            )
        return buildJsonObject {
            put("type", "FeatureCollection")
            put("features", features)
        }.toString()
    }

    /** The solid line also covers a striped run, which draws its ink on top. */
    fun solidFilter(): JsonArray = patternFilter("!=", DASHED_PATTERN)

    fun dashedFilter(): JsonArray = patternFilter("==", DASHED_PATTERN)

    fun stripedFilter(): JsonArray = patternFilter("==", STRIPED_PATTERN)

    /** The run's own colour, converted to a MapLibre colour value. */
    fun colourExpression(): Expression<ColorValue> =
        StyleExpression
            .value(
                buildJsonArray {
                    add("to-color")
                    add(propertyArray(COLOUR_PROPERTY))
                },
            ).cast()

    /**
     * The design's dash: 4 dp on and the strips' 2 dp gap off. MapLibre reads a
     * dash array in line-width units, so the dp values are divided by [widthDp].
     */
    fun dashArray(widthDp: Float): Expression<VectorValue<Number>> =
        StyleExpression
            .value(
                buildJsonArray {
                    add("literal")
                    addJsonArray {
                        for (value in dashSpec(widthDp)) {
                            add(value)
                        }
                    }
                },
            ).cast()

    /** The dash ratios [dashArray] encodes, so a test reads the design's dp values. */
    fun dashSpec(widthDp: Float): List<Number> = listOf(DASH_ON_DP / widthDp, DASH_OFF_DP / widthDp)

    /** The stripe ink as a Compose colour, or null when the style has none. */
    fun stripeColour(value: String?): Color? = value?.let(::parseColour)

    private fun patternName(pattern: RouteLinePattern): String =
        when (pattern) {
            RouteLinePattern.Solid -> SOLID_PATTERN
            RouteLinePattern.Dashed -> DASHED_PATTERN
            RouteLinePattern.Striped -> STRIPED_PATTERN
        }

    private fun patternFilter(
        operator: String,
        pattern: String,
    ): JsonArray =
        buildJsonArray {
            add(operator)
            add(propertyArray(PATTERN_PROPERTY))
            add(pattern)
        }

    private fun propertyArray(name: String): JsonArray =
        buildJsonArray {
            add("get")
            add(name)
        }

    private fun parseColour(value: String): Color? {
        val digits = value.removePrefix("#")
        if (digits.length != HEX_DIGITS) {
            return null
        }
        val rgb = digits.toLongOrNull(radix = RADIX) ?: return null
        return Color(OPAQUE or rgb)
    }

    private const val MIN_RUN_POINTS = 2
    private const val HEX_DIGITS = 6
    private const val RADIX = 16
    private const val OPAQUE = 0xFF000000L

    /** The design's dash period and its 2 px gap, in dp. */
    private const val DASH_ON_DP = 4f
    private const val DASH_OFF_DP = 2f
}
