package nl.r1a.grassmap.plugins.info.widgets

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.widget.AltitudeWidgetValue
import nl.r1a.grassmap.plugin.api.widget.CoordinatesWidgetValue
import nl.r1a.grassmap.plugin.api.widget.GpsInfoWidgetValue

/**
 * The top information widgets: altitude, coordinates and GPS info.
 *
 * The plugin owns no position source of its own: the host passes the location
 * flow, the camera, the DEM and the clock through [PluginContext.services]. It
 * shares one subscription to the fixes and one to the camera across the three
 * widgets, so a 1 Hz replay is not collected three times.
 *
 * Values are streams (section 4.7): the shell binds them by id, and the control
 * channel subscribes to the same ids. The widget descriptors only describe the
 * binding; drawing is T-0605.
 */
class InfoWidgetsPlugin(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Plugin {
    private var scope: CoroutineScope? = null

    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        val services = context.services
        val pluginScope = CoroutineScope(SupervisorJob() + dispatcher)
        scope?.cancel()
        scope = pluginScope

        val fixes =
            services.location.fixes
                .stateIn(pluginScope, SharingStarted.WhileSubscribed(SHARE_STOP_MILLIS), null)
        val camera =
            services.camera
                .stateIn(pluginScope, SharingStarted.WhileSubscribed(SHARE_STOP_MILLIS), null)

        val altitude =
            AltitudeWidgetProvider(
                fixes = fixes,
                elevation = services.elevation,
                clock = services.clock,
            )
        val coordinates =
            CoordinatesWidgetProvider(
                fixes = fixes,
                camera = camera.mapCenterCandidates(),
                clock = services.clock,
            )
        val gps =
            GpsInfoWidgetProvider(
                fixes = fixes,
                clock = services.clock,
            )

        context.addStream(
            StreamDescriptor(
                id = InfoWidgetsContract.altitudeStream,
                serializer = AltitudeWidgetValue.serializer(),
                flow = altitude.values,
            ),
        )
        context.addStream(
            StreamDescriptor(
                id = InfoWidgetsContract.coordinatesStream,
                serializer = CoordinatesWidgetValue.serializer(),
                flow = coordinates.values,
            ),
        )
        context.addStream(
            StreamDescriptor(
                id = InfoWidgetsContract.gpsStream,
                serializer = GpsInfoWidgetValue.serializer(),
                flow = gps.values,
            ),
        )
        context.addWidget(altitudeWidget())
        context.addWidget(coordinatesWidget())
        context.addWidget(gpsWidget())
        return Outcome.Success(Unit)
    }

    override fun deactivate() {
        scope?.cancel()
        scope = null
    }

    private companion object {
        /**
         * Keep the shared sources alive briefly after the last subscriber goes
         * away, so a widget that is re-bound after a screen rebuild does not
         * restart the replay.
         */
        const val SHARE_STOP_MILLIS = 5_000L
    }
}

/**
 * The camera state flow is nullable while no map surface is bound; the
 * coordinates provider takes a non-null camera flow, so drop the empty state.
 */
private fun StateFlow<Camera?>.mapCenterCandidates(): Flow<Camera> =
    flow {
        collect { camera ->
            if (camera != null) {
                emit(camera)
            }
        }
    }
