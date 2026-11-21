package nl.r1a.grassmaps.plugin.api.contract

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class PluginId(
    val value: String,
) {
    override fun toString(): String = value
}

@JvmInline
value class CommandId(
    val value: String,
) {
    override fun toString(): String = value
}

@JvmInline
value class StreamId(
    val value: String,
) {
    override fun toString(): String = value
}

@JvmInline
value class SwitchId(
    val value: String,
) {
    override fun toString(): String = value
}

@JvmInline
value class WidgetId(
    val value: String,
) {
    override fun toString(): String = value
}
