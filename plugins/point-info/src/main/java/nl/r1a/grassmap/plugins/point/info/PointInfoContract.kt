package nl.r1a.grassmap.plugins.point.info

import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.contract.WidgetId
import nl.r1a.grassmap.plugin.api.widget.WidgetCadence
import nl.r1a.grassmap.plugin.api.widget.WidgetDescriptor

/**
 * The ids, titles and value types the plugin declares in `plugin.json`.
 *
 * They live here as constants so activation, the app label, the tests and the
 * control channel all name the same stream and widget. `PointInfoManifestTest`
 * keeps the manifest in step with this contract.
 */
object PointInfoContract {
    const val PLUGIN_ID: String = "point.info"
    const val SELECTION_STREAM_ID: String = "point.info.selection"
    const val SELECTION_WIDGET_ID: String = "point.info.selection"
    const val SELECTION_TITLE: String = "plugin.point_info.selection"
    const val SELECTION_VALUE_TYPE: String = "nl.r1a.grassmap.plugins.point.info.SelectedPointValue"
    const val UPDATE_INTERVAL_MILLIS: Long = WidgetCadence.DEFAULT_INTERVAL_MILLIS

    val selectionStream: StreamId = StreamId(SELECTION_STREAM_ID)
}

internal fun selectionWidget(): WidgetDescriptor =
    WidgetDescriptor(
        id = WidgetId(PointInfoContract.SELECTION_WIDGET_ID),
        title = PointInfoContract.SELECTION_TITLE,
        valueType = PointInfoContract.SELECTION_VALUE_TYPE,
        streamId = PointInfoContract.selectionStream,
        updateIntervalMillis = PointInfoContract.UPDATE_INTERVAL_MILLIS,
    )
