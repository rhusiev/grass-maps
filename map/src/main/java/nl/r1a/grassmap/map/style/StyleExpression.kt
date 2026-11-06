package nl.r1a.grassmap.map.style

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.compose.expressions.ast.CallArgument
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.call
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.dsl.verbatim
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ExpressionValue

/**
 * Compiles a MapLibre style-spec expression from JSON into the strongly typed
 * expression AST that maplibre-compose accepts.
 *
 * The AST is written back as style JSON by the library, so the encoded value is
 * the same expression. Operators this module does not know fail the compilation
 * instead of being guessed at.
 */
internal object StyleExpression {
    private val KNOWN_OPERATORS =
        setOf(
            "get",
            "has",
            "properties",
            "geometry-type",
            "id",
            "feature-state",
            "line-progress",
            "accumulated",
            "within",
            "distance",
            "zoom",
            "heatmap-density",
            "literal",
            "case",
            "coalesce",
            "match",
            "step",
            "interpolate",
            "linear",
            "exponential",
            "cubic-bezier",
            "all",
            "any",
            "!",
            "==",
            "!=",
            "<",
            "<=",
            ">",
            ">=",
            "in",
            "!in",
            "length",
            "concat",
            "format",
            "is-supported-script",
            "upcase",
            "downcase",
            "to-string",
            "to-number",
            "to-boolean",
            "to-color",
            "to-rgba",
            "string",
            "number",
            "boolean",
            "rgb",
            "rgba",
            "image",
            "+",
            "-",
            "*",
            "/",
            "%",
            "^",
            "min",
            "max",
            "abs",
            "ceil",
            "floor",
            "round",
            "sqrt",
            "pow",
            "ln",
            "ln2",
            "log10",
            "log2",
            "e",
            "pi",
            "sin",
            "cos",
            "tan",
            "asin",
            "acos",
            "atan",
            "at",
            "index-of",
            "slice",
            "join",
            "replace",
            "split",
            "let",
            "var",
            "with",
        )

    fun expression(json: JsonElement): Expression<ExpressionValue> =
        when (json) {
            is JsonArray -> arrayExpression(json)
            else -> scalarExpression(json).cast()
        }

    fun boolean(json: JsonElement): Expression<BooleanValue> = expression(json).cast()

    /** An array used as a property value: an operator call, or a literal array. */
    fun value(json: JsonElement): Expression<ExpressionValue> = expression(json)

    /**
     * Legacy filter syntax (`["==", "kind", "hospital"]`) and expression filters
     * (`["==", ["get", "kind"], "hospital"]`) both appear in MapLibre styles.
     */
    fun filter(json: JsonElement): Expression<BooleanValue> {
        val array = json as? JsonArray ?: return boolean(json)
        val operator = (array.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (operator == null || operator !in LEGACY_FILTER_OPERATORS) {
            return boolean(json)
        }
        if (operator in COMPARISON_OPERATORS && array.size > 1 && array[1] is JsonArray) {
            return boolean(json)
        }
        if ((operator == "in" || operator == "!in") && array.size > 1 && array[1] is JsonArray) {
            return boolean(json)
        }
        return legacyFilter(array)
    }

    private fun legacyFilter(array: JsonArray): Expression<BooleanValue> {
        val operator = (array[0] as JsonPrimitive).content
        // A legacy predicate can contain a nested expression predicate.
        if (operator in COMPARISON_OPERATORS && array.size > 1 && array[1] is JsonArray) {
            return boolean(array)
        }
        if ((operator == "in" || operator == "!in") && array.size > 1 && array[1] is JsonArray) {
            return boolean(array)
        }
        return when (operator) {
            "has" -> {
                booleanCall("has", const(propertyName(array[1])))
            }

            "!has" -> {
                booleanCall("!", booleanCall("has", const(propertyName(array[1]))))
            }

            "==", "!=", "<", "<=", ">", ">=" -> {
                booleanCall(operator, propertyAccess(array[1]), argument(array, 2))
            }

            "in" -> {
                booleanCall("in", propertyAccess(array[1]), literal(array, 2))
            }

            "!in" -> {
                booleanCall("!", booleanCall("in", propertyAccess(array[1]), literal(array, 2)))
            }

            "all", "any" -> {
                booleanCall(operator, *array.drop(1).map { legacyFilter(it as JsonArray) }.toTypedArray())
            }

            "!" -> {
                booleanCall("!", legacyFilter(array[1] as JsonArray))
            }

            "none" -> {
                booleanCall(
                    "!",
                    booleanCall(
                        "any",
                        *array.drop(1).map { legacyFilter(it as JsonArray) }.toTypedArray(),
                    ),
                )
            }

            else -> {
                throw StyleCompileException("Unsupported filter operator '$operator'")
            }
        }
    }

    private fun booleanCall(
        operator: String,
        vararg args: CallArgument,
    ): Expression<BooleanValue> = call<BooleanValue>(operator, args.toList())

    private fun valueCall(
        operator: String,
        vararg args: CallArgument,
    ): Expression<ExpressionValue> = call<ExpressionValue>(operator, args.toList())

    private fun propertyName(json: JsonElement): String =
        (json as? JsonPrimitive)?.content ?: throw StyleCompileException("Not a property name: $json")

    private fun propertyAccess(json: JsonElement): CallArgument {
        val name = propertyName(json)
        return if (name == "\$type") valueCall("geometry-type") else valueCall("get", const(name))
    }

    private fun argument(
        array: JsonArray,
        index: Int,
    ): CallArgument = if (index in array.indices) value(array[index]) else const(0)

    private fun literal(
        array: JsonArray,
        from: Int,
    ): Expression<ExpressionValue> = valueCall("literal", verbatim(JsonArray(array.drop(from))))

    private val COMPARISON_OPERATORS = setOf("==", "!=", "<", "<=", ">", ">=")

    private val LEGACY_FILTER_OPERATORS =
        COMPARISON_OPERATORS + setOf("in", "!in", "has", "!has", "all", "any", "!", "none")

    private fun arrayExpression(json: JsonArray): Expression<ExpressionValue> {
        val operator = (json.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (operator == null || operator !in KNOWN_OPERATORS) {
            return literalArray(json)
        }
        return when (operator) {
            "literal" -> {
                valueCall("literal", verbatim(json.valueAt(1)))
            }

            "interpolate" -> {
                // ["interpolate", interpolation, input, stop, output, ...]
                val args = mutableListOf<CallArgument>(verbatim(json.valueAt(1)))
                args += argument(json.valueAt(2))
                var index = 3
                while (index < json.size) {
                    args += verbatim(json.valueAt(index))
                    if (index + 1 < json.size) {
                        args += argument(json.valueAt(index + 1))
                    }
                    index += 2
                }
                valueCall("interpolate", *args.toTypedArray())
            }

            "step" -> {
                // ["step", input, output0, stop1, output1, ...]
                val args = mutableListOf<CallArgument>(argument(json.valueAt(1)))
                if (json.size > 2) {
                    args += argument(json.valueAt(2))
                }
                var index = 3
                while (index < json.size) {
                    args += verbatim(json.valueAt(index))
                    if (index + 1 < json.size) {
                        args += argument(json.valueAt(index + 1))
                    }
                    index += 2
                }
                valueCall("step", *args.toTypedArray())
            }

            "format" -> {
                // ["format", input, options, input, options, ...]
                val args = mutableListOf<CallArgument>()
                var index = 1
                while (index < json.size) {
                    val part = json.valueAt(index)
                    if (part is JsonObject) {
                        args += verbatim(part)
                    } else {
                        args += argument(part)
                    }
                    index += 1
                }
                valueCall("format", *args.toTypedArray())
            }

            else -> {
                valueCall(operator, *json.drop(1).map { argument(it) }.toTypedArray())
            }
        }
    }

    private fun argument(json: JsonElement): CallArgument =
        when (json) {
            is JsonObject -> verbatim(json)
            is JsonArray -> if (startsWithOperator(json)) arrayExpression(json) else literalArray(json)
            else -> scalarExpression(json)
        }

    private fun scalarExpression(json: JsonElement): Expression<*> =
        when (json) {
            JsonNull -> {
                nil()
            }

            is JsonPrimitive -> {
                when {
                    json.isString -> const(json.content)
                    json.booleanOrNull != null -> const(json.booleanOrNull == true)
                    else -> numberExpression(json)
                }
            }

            else -> {
                literalArray(JsonArray(listOf(json)))
            }
        }

    private fun numberExpression(json: JsonPrimitive): Expression<*> {
        val double = json.doubleOrNull ?: throw StyleCompileException("Not a number: $json")
        return if (double % 1.0 == 0.0 && double >= Int.MIN_VALUE && double <= Int.MAX_VALUE) {
            const(double.toInt())
        } else {
            const(double.toFloat())
        }
    }

    private fun literalArray(json: JsonArray): Expression<ExpressionValue> = valueCall("literal", verbatim(json))

    private fun startsWithOperator(json: JsonArray): Boolean {
        val first = (json.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString } ?: return false
        return first.content in KNOWN_OPERATORS
    }

    private fun JsonArray.valueAt(index: Int): JsonElement = if (index in indices) this[index] else JsonNull
}

internal class StyleCompileException(
    message: String,
) : Exception(message)
