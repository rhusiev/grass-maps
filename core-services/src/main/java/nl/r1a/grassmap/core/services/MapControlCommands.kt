package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.contract.CommandId

/**
 * The map commands a configurable control bar can bind. The IDs are the public
 * names on the command bus, not implementation details of the app module.
 */
object MapControlCommands {
    val ZOOM_IN: CommandId = CommandId("core.map.zoom_in")
    val ZOOM_OUT: CommandId = CommandId("core.map.zoom_out")
    val NORTH_UP: CommandId = CommandId("core.map.north_up")
    val FOLLOW: CommandId = CommandId("core.map.follow")

    /** Opens the start/end route planner (T-0302); the command belongs to `plugins/route-planner`. */
    val ROUTE_PLANNER: CommandId = CommandId("route.planner.begin")

    /** The order a profile uses until it saves its own. */
    val DEFAULT_ORDER: List<CommandId> = listOf(ZOOM_IN, ZOOM_OUT, NORTH_UP, FOLLOW, ROUTE_PLANNER)
}
