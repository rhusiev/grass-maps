package nl.r1a.grassmap.plugin.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One typed way attribute. The vocabulary is the provider's, so the host and
 * the routers only compare and display values; they never parse them.
 *
 * The type is serializable because the route planner carries its route's
 * segments, attributes included, in the published plan state.
 */
@Serializable
sealed interface AttributeValue {
    @Serializable
    @SerialName("number")
    data class NumberValue(
        val value: Double,
    ) : AttributeValue

    @Serializable
    @SerialName("text")
    data class TextValue(
        val value: String,
    ) : AttributeValue

    @Serializable
    @SerialName("boolean")
    data class BooleanValue(
        val value: Boolean,
    ) : AttributeValue
}
