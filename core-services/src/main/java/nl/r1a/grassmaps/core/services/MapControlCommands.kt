package nl.r1a.grassmaps.core.services

import nl.r1a.grassmaps.plugin.api.contract.CommandId

/**
 * The map buttons a profile can place on its control bar (T-0606).
 *
 * Most entries are command bus ids. [SAVED] and [TRACKS] are app control ids
 * that the map screen maps to its saved lists and track library; they never
 * reach the command bus.
 */
object MapControlCommands {
    val ZOOM_IN: CommandId = CommandId("core.map.zoom_in")
    val ZOOM_OUT: CommandId = CommandId("core.map.zoom_out")
    val NORTH_UP: CommandId = CommandId("core.map.north_up")
    val FOLLOW: CommandId = CommandId("core.map.follow")

    /** Opens the start/end route planner (T-0302); the command belongs to `planner`. */
    val ROUTE_PLANNER: CommandId = CommandId("route.planner.begin")

    /** Opens the saved lists over the map (T-0213). */
    val SAVED: CommandId = CommandId("app.map.saved")

    /** Opens the track library over the map (T-0414). */
    val TRACKS: CommandId = CommandId("app.map.tracks")

    /** Every button the layout editor offers, in the order it lists them. */
    val CONFIGURABLE: List<CommandId> =
        listOf(ZOOM_IN, ZOOM_OUT, NORTH_UP, FOLLOW, ROUTE_PLANNER, SAVED, TRACKS)

    /** The order a profile uses until it saves its own. */
    val DEFAULT_ORDER: List<CommandId> = listOf(ZOOM_IN, ZOOM_OUT, NORTH_UP, FOLLOW, ROUTE_PLANNER)
}
