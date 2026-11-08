package nl.r1a.grassmap.plugins.map.controls

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor

/**
 * The first-party map-controls plugin (T-0106).
 *
 * It publishes the current camera mode as a stream so the control bar, widgets
 * and the control channel read the same state. The camera moves themselves stay
 * with the host command layer, which owns the map session.
 */
class MapControlsPlugin(
    private val modes: CameraModeStore = MapControls.modes,
) : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        context.addStream(
            StreamDescriptor(
                id = MapControlsContract.MODE_STREAM_ID,
                serializer = CameraMode.serializer(),
                flow = modes.mode,
            ),
        )
        return Outcome.Success(Unit)
    }
}
