package nl.r1a.grassmap.plugins.map.controls

import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId

/** The public names of the map-controls plugin's contributions. */
object MapControlsContract {
    val PLUGIN_ID: PluginId = PluginId("map.controls")
    val MODE_STREAM_ID: StreamId = StreamId("map.controls.mode")

    const val MODE_VALUE_TYPE: String =
        "nl.r1a.grassmap.plugins.map.controls.CameraMode"
    const val MODE_SERIALIZER: String =
        "nl.r1a.grassmap.plugins.map.controls.CameraMode.serializer"

    const val POSITION_OFFSET_SETTING: String = "map.controls.position_offset_y"
}
