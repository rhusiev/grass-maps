package nl.r1a.grassmap.plugins.route.planner

import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.StreamId

/**
 * The ids and value types the route-planner plugin declares (T-0302).
 *
 * Every id is namespaced under the plugin id and `plugin.json` must name the
 * same values; `RoutePlannerManifestTest` keeps the two in step.
 */
object RoutePlannerContract {
    const val PLUGIN_ID: String = "route.planner"
    const val STATE_STREAM_ID: String = "route.planner.state"
    const val STATE_VALUE_TYPE: String = "nl.r1a.grassmap.plugin.api.model.RoutePlanState"
    const val STATE_SERIALIZER: String = "nl.r1a.grassmap.plugin.api.model.RoutePlanState.serializer"

    const val BEGIN: String = "route.planner.begin"
    const val SET_POINT: String = "route.planner.set_point"
    const val PLAN: String = "route.planner.plan"
    const val SWAP: String = "route.planner.swap"
    const val SELECT: String = "route.planner.select"
    const val START: String = "route.planner.start"
    const val CLEAR: String = "route.planner.clear"

    val stateStream: StreamId = StreamId(STATE_STREAM_ID)

    val begin: CommandId = CommandId(BEGIN)
    val setPoint: CommandId = CommandId(SET_POINT)
    val plan: CommandId = CommandId(PLAN)
    val swap: CommandId = CommandId(SWAP)
    val select: CommandId = CommandId(SELECT)
    val start: CommandId = CommandId(START)
    val clear: CommandId = CommandId(CLEAR)

    val commands: List<CommandId> = listOf(begin, setPoint, plan, swap, select, start, clear)
}
