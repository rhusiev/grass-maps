package nl.r1a.grassmap.plugins.dev.tools

import kotlinx.serialization.Serializable

/**
 * One sample of the development-tools panel. [framesPerSecond] and
 * [usedMemoryBytes] come from the host's [nl.r1a.grassmap.plugin.api.host.DevToolsAccess],
 * so the plugin reads state instead of measuring anything itself.
 */
@Serializable
data class DevToolsPanelState(
    val replayActive: Boolean = false,
    val framesPerSecond: Double = 0.0,
    val usedMemoryBytes: Long = 0L,
)
