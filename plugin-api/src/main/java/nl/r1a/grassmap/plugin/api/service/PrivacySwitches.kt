package nl.r1a.grassmap.plugin.api.service

import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome

/** The state of one privacy switch, as the settings screen shows it. */
data class SwitchState(
    val switch: SwitchId,
    val enabled: Boolean,
    val requests: Long,
)

/**
 * The privacy switches that gate network contact. Every switch starts off and
 * the user turns one on to allow that kind of contact.
 *
 * [recordRequest] is called by [NetworkGate] for each request it allows, so the
 * settings screen can say how often a switch permitted contact. Nothing else
 * calls it.
 */
interface PrivacySwitches {
    /**
     * The state of [switch]. A switch that is not registered reads as off, so
     * an unknown switch can never let a request through.
     */
    fun state(switch: SwitchId): SwitchState

    /** Every registered switch, in the order settings shows them. */
    fun states(): List<SwitchState>

    /** Turns [switch] on or off. An unregistered switch is [AppError.NotFound]. */
    suspend fun setEnabled(
        switch: SwitchId,
        enabled: Boolean,
    ): Outcome<Unit>

    /** Counts one allowed request against [switch]. */
    fun recordRequest(switch: SwitchId)
}
