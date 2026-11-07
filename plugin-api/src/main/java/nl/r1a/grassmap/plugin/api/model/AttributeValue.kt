package nl.r1a.grassmap.plugin.api.model

/**
 * One typed way attribute. The vocabulary is the provider's, so the host and
 * the routers only compare and display values; they never parse them.
 */
sealed interface AttributeValue {
    data class NumberValue(
        val value: Double,
    ) : AttributeValue

    data class TextValue(
        val value: String,
    ) : AttributeValue

    data class BooleanValue(
        val value: Boolean,
    ) : AttributeValue
}
