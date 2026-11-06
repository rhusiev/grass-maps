package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.contract.SwitchId
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A [SwitchStateStore] that keeps state for the life of the process. It is the
 * stand-in until the persistent settings store exists, and the fake the switch
 * tests start from.
 */
class InMemorySwitchStateStore : SwitchStateStore {
    private val enabledSwitches = Collections.newSetFromMap(ConcurrentHashMap<SwitchId, Boolean>())
    private val requestCounts = ConcurrentHashMap<SwitchId, AtomicLong>()

    override fun isEnabled(switch: SwitchId): Boolean = switch in enabledSwitches

    override fun setEnabled(
        switch: SwitchId,
        enabled: Boolean,
    ) {
        if (enabled) {
            enabledSwitches.add(switch)
        } else {
            enabledSwitches.remove(switch)
        }
    }

    override fun requests(switch: SwitchId): Long = requestCounts[switch]?.get() ?: 0L

    override fun recordRequest(switch: SwitchId) {
        requestCounts.computeIfAbsent(switch) { AtomicLong() }.incrementAndGet()
    }
}
