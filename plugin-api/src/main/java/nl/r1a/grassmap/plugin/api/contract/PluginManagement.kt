package nl.r1a.grassmaps.plugin.api.contract

import kotlinx.serialization.Serializable

/**
 * The host-owned ids behind plugin toggling (T-0607). Toggling is a command
 * and a stream, not a host interface, so a plugin module reads and switches
 * plugins without importing `core`.
 */
object PluginManagement {
    const val STATE_STREAM_ID: String = "core.plugins.state"
    const val SET_ENABLED_ID: String = "core.plugins.set_enabled"

    val stateStream: StreamId = StreamId(STATE_STREAM_ID)
    val setEnabled: CommandId = CommandId(SET_ENABLED_ID)
}

/** Parameters of `core.plugins.set_enabled`. */
@Serializable
data class SetEnabledParams(
    val plugin: PluginId,
    val enabled: Boolean,
)
