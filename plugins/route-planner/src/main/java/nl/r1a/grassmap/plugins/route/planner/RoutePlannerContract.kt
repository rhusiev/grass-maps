package nl.r1a.grassmap.plugins.route.planner

import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.StreamId

/**
 * The ids and value types the route-planner plugin declares (T-0302, vias in
 * T-0303).
 *
 * Every id is namespaced under the plugin id and `plugin.json` must name the
 * same values; `RoutePlannerManifestTest` keeps the two in step.
 */
object RoutePlannerContract {
    const val PLUGIN_ID: String = "route.planner"
    const val STATE_STREAM_ID: String = "route.planner.state"
    const val HISTORY_STREAM_ID: String = "route.planner.history"
    const val STATE_VALUE_TYPE: String = "nl.r1a.grassmap.plugin.api.model.RoutePlanState"
    const val STATE_SERIALIZER: String = "nl.r1a.grassmap.plugin.api.model.RoutePlanState.serializer"
    const val HISTORY_VALUE_TYPE: String = "kotlin.collections.ArrayList"
    const val HISTORY_SERIALIZER: String = "kotlinx.serialization.builtins.ListSerializer"

    const val BEGIN: String = "route.planner.begin"
    const val SET_POINT: String = "route.planner.set_point"
    const val ADD_VIA: String = "route.planner.add_via"
    const val REMOVE_VIA: String = "route.planner.remove_via"
    const val MOVE_VIA: String = "route.planner.move_via"
    const val PLAN: String = "route.planner.plan"
    const val SWAP: String = "route.planner.swap"
    const val SELECT: String = "route.planner.select"
    const val START: String = "route.planner.start"
    const val CLEAR: String = "route.planner.clear"
    const val UNDO: String = "route.planner.undo"
    const val REDO: String = "route.planner.redo"
    const val REUSE_HISTORY: String = "route.planner.reuse_history"

    val stateStream: StreamId = StreamId(STATE_STREAM_ID)
    val historyStream: StreamId = StreamId(HISTORY_STREAM_ID)

    val begin: CommandId = CommandId(BEGIN)
    val setPoint: CommandId = CommandId(SET_POINT)
    val addVia: CommandId = CommandId(ADD_VIA)
    val removeVia: CommandId = CommandId(REMOVE_VIA)
    val moveVia: CommandId = CommandId(MOVE_VIA)
    val plan: CommandId = CommandId(PLAN)
    val swap: CommandId = CommandId(SWAP)
    val select: CommandId = CommandId(SELECT)
    val start: CommandId = CommandId(START)
    val clear: CommandId = CommandId(CLEAR)
    val undo: CommandId = CommandId(UNDO)
    val redo: CommandId = CommandId(REDO)
    val reuseHistory: CommandId = CommandId(REUSE_HISTORY)

    val commands: List<CommandId> =
        listOf(begin, setPoint, addVia, removeVia, moveVia, plan, swap, select, start, clear, undo, redo, reuseHistory)
}
