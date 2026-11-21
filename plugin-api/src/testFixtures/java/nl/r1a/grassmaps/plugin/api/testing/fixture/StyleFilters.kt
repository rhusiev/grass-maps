package nl.r1a.grassmaps.plugin.api.testing.fixture

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Evaluates the legacy MapLibre filter subset the authored styles use.
 *
 * A style test needs to know which features a layer draws, not only which
 * filter JSON it names. This reads the same legacy predicates the style
 * documents author (`has`, comparisons, `in`, `all`, `any`, `!`, `none`).
 * A missing property fails every comparison except `!=`, which is true
 * because the missing value is not the literal.
 */
object StyleFilters {
    fun matches(
        filter: JsonElement?,
        properties: Map<String, Any?>,
    ): Boolean = filter == null || evaluate(filter, properties)

    private fun evaluate(
        filter: JsonElement,
        properties: Map<String, Any?>,
    ): Boolean {
        val array = filter as? JsonArray ?: error("a filter must be an array: $filter")
        val operator =
            (array.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("a filter must start with an operator: $filter")
        return when (operator) {
            "all" -> {
                array.drop(1).all { evaluate(it, properties) }
            }

            "any" -> {
                array.drop(1).any { evaluate(it, properties) }
            }

            "none" -> {
                array.drop(1).none { evaluate(it, properties) }
            }

            "!" -> {
                !evaluate(array[1], properties)
            }

            "has" -> {
                properties.containsKey(propertyName(array[1]))
            }

            "!has" -> {
                !properties.containsKey(propertyName(array[1]))
            }

            "==" -> {
                equal(properties[propertyName(array[1])], literal(array[2]))
            }

            "!=" -> {
                !equal(properties[propertyName(array[1])], literal(array[2]))
            }

            "<", "<=", ">", ">=" -> {
                ordered(operator, properties, array)
            }

            "in" -> {
                val value = properties[propertyName(array[1])]
                value != null && array.drop(2).any { equal(value, literal(it)) }
            }

            "!in" -> {
                val value = properties[propertyName(array[1])]
                value == null || array.drop(2).none { equal(value, literal(it)) }
            }

            else -> {
                error("unsupported filter operator '$operator'")
            }
        }
    }

    private fun ordered(
        operator: String,
        properties: Map<String, Any?>,
        array: JsonArray,
    ): Boolean {
        val left = number(properties[propertyName(array[1])]) ?: return false
        val right = number(literal(array[2])) ?: error("$operator needs a number: ${array[2]}")
        return when (operator) {
            "<" -> left < right
            "<=" -> left <= right
            ">" -> left > right
            else -> left >= right
        }
    }

    private fun propertyName(element: JsonElement): String =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("not a property name: $element")

    private fun literal(element: JsonElement): Any? =
        when (element) {
            JsonNull -> {
                null
            }

            is JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.booleanOrNull != null -> element.booleanOrNull
                    else -> element.doubleOrNull ?: error("unsupported literal: $element")
                }
            }

            else -> {
                error("unsupported literal: $element")
            }
        }

    private fun equal(
        value: Any?,
        literal: Any?,
    ): Boolean =
        when {
            value == null || literal == null -> value == literal
            value is Number && literal is Number -> value.toDouble() == literal.toDouble()
            else -> value == literal
        }

    private fun number(value: Any?): Double? =
        when (value) {
            is Number -> value.toDouble()
            is JsonPrimitive -> value.doubleOrNull
            else -> null
        }
}
