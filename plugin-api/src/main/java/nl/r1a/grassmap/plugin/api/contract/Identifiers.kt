package nl.r1a.grassmap.plugin.api.contract

@JvmInline
value class PluginId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class CommandId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class StreamId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class SwitchId(val value: String) {
    override fun toString(): String = value
}
