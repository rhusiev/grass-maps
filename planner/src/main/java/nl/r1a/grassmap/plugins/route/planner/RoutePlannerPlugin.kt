package nl.r1a.grassmap.plugins.route.planner

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.builtins.ListSerializer
import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.model.RouteHistoryEntry
import nl.r1a.grassmap.plugin.api.model.RoutePlanState

/**
 * The first-party route planner (T-0302, vias in T-0303, edit history and
 * reuse in T-0304).
 *
 * It owns the start/end plan, its intermediate stops, the edit history behind
 * undo and redo, and the commands the map and the control channel drive. The
 * state machine is `routing.RoutePlanner`; the engine is resolved through
 * [nl.r1a.grassmap.plugin.api.host.RoutingEngineAccess], so the plugin names no
 * provider, and the reuse history through
 * [nl.r1a.grassmap.plugin.api.host.RouteHistoryAccess], so it names no path.
 * The shell draws the preview from the published state and never recomputes a
 * route.
 *
 * The plugin also owns the scope of the annotate pass (T-0409): a produced
 * route is annotated after it is published, on this scope, so the preview never
 * waits for the engine and a deactivated plugin stops asking. The outcome is
 * logged through [nl.r1a.grassmap.plugin.api.host.PluginServices.logger].
 */
class RoutePlannerPlugin(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Plugin {
    private var scope: CoroutineScope? = null

    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        val pluginScope = CoroutineScope(SupervisorJob() + dispatcher)
        scope?.cancel()
        scope = pluginScope
        val session =
            RoutePlannerSession(
                engines = context.services.routingEngines,
                clock = context.services.clock,
                history = context.services.routeHistory,
                options = context.services.routingOptions,
                annotationScope = pluginScope,
                log = { message -> context.services.logger.warn(LOG_TAG, message) },
            )
        session.loadHistory()
        context.addCommand(BeginRoutePlanCommand(session))
        context.addCommand(SetRoutePlanPointCommand(session))
        context.addCommand(AddRoutePlanViaCommand(session))
        context.addCommand(RemoveRoutePlanViaCommand(session))
        context.addCommand(MoveRoutePlanViaCommand(session))
        context.addCommand(PlanRouteCommand(session))
        context.addCommand(SetRoutePlanPreferencesCommand(session))
        context.addCommand(ResetRoutePlanPreferencesCommand(session))
        context.addCommand(SwapRoutePlanCommand(session))
        context.addCommand(SelectRoutePlanOptionCommand(session))
        context.addCommand(StartRoutePlanCommand(session))
        context.addCommand(ClearRoutePlanCommand(session))
        context.addCommand(UndoRoutePlanCommand(session))
        context.addCommand(RedoRoutePlanCommand(session))
        context.addCommand(ReuseRouteHistoryCommand(session))
        context.addStream(
            StreamDescriptor(
                id = RoutePlannerContract.stateStream,
                serializer = RoutePlanState.serializer(),
                flow = session.state,
            ),
        )
        context.addStream(
            StreamDescriptor(
                id = RoutePlannerContract.historyStream,
                serializer = ListSerializer(RouteHistoryEntry.serializer()),
                flow = session.recent,
            ),
        )
        return Outcome.Success(Unit)
    }

    override fun deactivate() {
        scope?.cancel()
        scope = null
    }

    private companion object {
        const val LOG_TAG = "route.planner"
    }
}
