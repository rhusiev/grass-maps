package nl.r1a.grassmap.plugins.nav.simulator

import kotlinx.serialization.Serializable
import nl.r1a.grassmap.navigation.NavigationPhase

/** The session phase as the simulator publishes it over the stream. */
@Serializable
enum class SimulatorPhase {
    Idle,
    Following,
    Rerouting,
    Arrived,
    Stopped,
    ;

    companion object {
        fun from(phase: NavigationPhase): SimulatorPhase =
            when (phase) {
                NavigationPhase.Idle -> Idle
                NavigationPhase.Following -> Following
                NavigationPhase.Rerouting -> Rerouting
                NavigationPhase.Arrived -> Arrived
                NavigationPhase.Stopped -> Stopped
            }
    }
}

/**
 * One simulated run's state: the session's phase, how many fixes the session
 * consumed, whether it arrived, and the remaining distance. The command result
 * and the `nav.simulator.state` stream carry this same value.
 */
@Serializable
data class SimulatorStatus(
    val phase: SimulatorPhase = SimulatorPhase.Idle,
    val fixCount: Int = 0,
    val arrived: Boolean = false,
    val running: Boolean = false,
    val remainingDistanceMeters: Double = 0.0,
    val lastFixTimeMillis: Long? = null,
    val speedKmh: Double = NavSimulatorContract.SPEED_KMH,
    val cadenceMillis: Long = NavSimulatorContract.CADENCE_MILLIS,
)
