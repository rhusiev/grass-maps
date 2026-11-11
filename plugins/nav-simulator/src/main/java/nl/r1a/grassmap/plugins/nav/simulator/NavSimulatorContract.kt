package nl.r1a.grassmap.plugins.nav.simulator

import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.StreamId

/**
 * The ids and constants the nav-simulator plugin declares (T-0315).
 *
 * Every id is namespaced under the plugin id and `plugin.json` must name the
 * same values; `NavSimulatorManifestTest` keeps the two in step.
 */
object NavSimulatorContract {
    const val PLUGIN_ID: String = "nav.simulator"
    const val STATUS_STREAM_ID: String = "nav.simulator.state"
    const val START_COMMAND_ID: String = "nav.simulator.start"
    const val STOP_COMMAND_ID: String = "nav.simulator.stop"

    const val STATUS_VALUE_TYPE: String = "nl.r1a.grassmap.plugins.nav.simulator.SimulatorStatus"
    const val STATUS_SERIALIZER: String = "$STATUS_VALUE_TYPE.serializer"

    /** The planner's published state, the simulator's route when none is passed. */
    const val CURRENT_ROUTE_STREAM_ID: String = "route.planner.state"

    /** The acceptance speed: 5 km/h. */
    const val SPEED_KMH: Double = 5.0
    const val SPEED_METERS_PER_SECOND: Double = SPEED_KMH / 3.6

    /**
     * One fix per simulated second. At 5 km/h each fix lands 1.389 m further
     * along the line, so the cadence is the simulation clock, not wall time.
     */
    const val CADENCE_MILLIS: Long = 1_000L

    /** 2026-01-01T00:00:00Z, the base the navigation replays use too. */
    const val START_EPOCH_MILLIS: Long = 1_767_225_600_000L

    val statusStream: StreamId = StreamId(STATUS_STREAM_ID)
    val start: CommandId = CommandId(START_COMMAND_ID)
    val stop: CommandId = CommandId(STOP_COMMAND_ID)
    val commands: List<CommandId> = listOf(start, stop)
}
