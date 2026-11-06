package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.PrivacySwitches
import nl.r1a.grassmap.plugin.api.service.SwitchState

/**
 * The v1 [PrivacySwitches]: a fixed registry of switches over a
 * [SwitchStateStore]. A switch that is not in the registry reads as off and
 * cannot be turned on, so a new kind of network contact needs a code change
 * and a user decision.
 */
class PrivacySwitchSettings(
    registry: List<SwitchId> = NetworkSwitches.all,
    private val store: SwitchStateStore = InMemorySwitchStateStore(),
) : PrivacySwitches {
    private val registered: Set<SwitchId> = registry.toSet()

    override fun state(switch: SwitchId): SwitchState =
        SwitchState(
            switch = switch,
            enabled = switch in registered && store.isEnabled(switch),
            requests = store.requests(switch),
        )

    override fun states(): List<SwitchState> = registered.map(::state)

    override suspend fun setEnabled(
        switch: SwitchId,
        enabled: Boolean,
    ): Outcome<Unit> {
        if (switch !in registered) {
            return Outcome.Failure(AppError.NotFound)
        }
        store.setEnabled(switch, enabled)
        return Outcome.Success(Unit)
    }

    override fun recordRequest(switch: SwitchId) {
        store.recordRequest(switch)
    }
}
