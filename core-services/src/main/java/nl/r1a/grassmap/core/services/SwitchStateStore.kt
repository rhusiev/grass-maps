package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.contract.SwitchId

/**
 * Backing storage for privacy switch state and its request counters. It keeps
 * the switch contract free of a concrete settings store; the real persistent
 * store arrives with the settings task.
 */
interface SwitchStateStore {
    fun isEnabled(switch: SwitchId): Boolean

    fun setEnabled(
        switch: SwitchId,
        enabled: Boolean,
    )

    fun requests(switch: SwitchId): Long

    fun recordRequest(switch: SwitchId)
}
