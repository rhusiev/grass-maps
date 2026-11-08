package nl.r1a.grassmap.plugins.point.info

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor

/**
 * The first-party point-info plugin (T-0112).
 *
 * It publishes the distance and azimuth from the current fix, or the map
 * centre when there is no fix, to the point a long press selected. The
 * selection itself is written by the app from the map gesture into
 * [PointInfo.selection]; this plugin only reads it, so it needs no map type and
 * stays on `:plugin-api`.
 *
 * Values are streams (architecture section 4.7): the map label and the widget
 * bind the same id, and the control channel can subscribe to it.
 */
class PointInfoPlugin(
    private val selection: SelectedPointStore = PointInfo.selection,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Plugin {
    private var scope: CoroutineScope? = null

    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        val services = context.services
        val pluginScope = CoroutineScope(SupervisorJob() + dispatcher)
        scope?.cancel()
        scope = pluginScope

        // A null initial value means "nothing yet", so the combine always has a
        // value from each source and a selection is never held back until the
        // first fix or the first camera arrives.
        val fixes =
            services.location.fixes
                .stateIn(pluginScope, SharingStarted.WhileSubscribed(SHARE_STOP_MILLIS), null)
        val centres =
            services.camera
                .map { camera -> camera.center }
                .stateIn(pluginScope, SharingStarted.WhileSubscribed(SHARE_STOP_MILLIS), null)
        val calculator =
            PointInfoCalculator(
                selection = selection.point,
                fixes = fixes,
                centres = centres,
                clock = services.clock,
            )

        context.addStream(
            StreamDescriptor(
                id = PointInfoContract.selectionStream,
                serializer = SelectedPointValue.serializer(),
                flow = calculator.values,
            ),
        )
        context.addWidget(selectionWidget())
        return Outcome.Success(Unit)
    }

    override fun deactivate() {
        scope?.cancel()
        scope = null
    }

    private companion object {
        /**
         * Keep the shared sources alive briefly after the last subscriber goes
         * away, so a label that is re-bound after a screen rebuild does not
         * restart the location flow.
         */
        const val SHARE_STOP_MILLIS = 5_000L
    }
}
