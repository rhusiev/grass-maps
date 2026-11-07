package nl.r1a.grassmap.plugins.info.widgets

import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.contract.WidgetId
import nl.r1a.grassmap.plugin.api.widget.WidgetCadence
import nl.r1a.grassmap.plugin.api.widget.WidgetDescriptor

/**
 * The ids, titles and value types the plugin declares in `plugin.json`.
 *
 * They live here as constants so the activation code, the tests and the
 * control-channel evidence all name the same widget. `InfoWidgetsContractTest`
 * checks the value types against the serializers, and the manifest lint checks
 * the rest.
 */
object InfoWidgetsContract {
    const val PLUGIN_ID: String = "info.widgets"
    const val ALTITUDE_STREAM_ID: String = "info.widgets.altitude"
    const val COORDINATES_STREAM_ID: String = "info.widgets.coordinates"
    const val GPS_STREAM_ID: String = "info.widgets.gps"

    const val ALTITUDE_TITLE: String = "plugin.info_widgets.altitude"
    const val COORDINATES_TITLE: String = "plugin.info_widgets.coordinates"
    const val GPS_TITLE: String = "plugin.info_widgets.gps"

    const val ALTITUDE_VALUE_TYPE: String = "nl.r1a.grassmap.plugin.api.widget.AltitudeWidgetValue"
    const val COORDINATES_VALUE_TYPE: String = "nl.r1a.grassmap.plugin.api.widget.CoordinatesWidgetValue"
    const val GPS_VALUE_TYPE: String = "nl.r1a.grassmap.plugin.api.widget.GpsInfoWidgetValue"

    const val UPDATE_INTERVAL_MILLIS: Long = WidgetCadence.DEFAULT_INTERVAL_MILLIS

    val altitudeStream: StreamId = StreamId(ALTITUDE_STREAM_ID)
    val coordinatesStream: StreamId = StreamId(COORDINATES_STREAM_ID)
    val gpsStream: StreamId = StreamId(GPS_STREAM_ID)
}

internal fun altitudeWidget(): WidgetDescriptor =
    WidgetDescriptor(
        id = WidgetId(InfoWidgetsContract.ALTITUDE_STREAM_ID),
        title = InfoWidgetsContract.ALTITUDE_TITLE,
        valueType = InfoWidgetsContract.ALTITUDE_VALUE_TYPE,
        streamId = InfoWidgetsContract.altitudeStream,
        updateIntervalMillis = InfoWidgetsContract.UPDATE_INTERVAL_MILLIS,
    )

internal fun coordinatesWidget(): WidgetDescriptor =
    WidgetDescriptor(
        id = WidgetId(InfoWidgetsContract.COORDINATES_STREAM_ID),
        title = InfoWidgetsContract.COORDINATES_TITLE,
        valueType = InfoWidgetsContract.COORDINATES_VALUE_TYPE,
        streamId = InfoWidgetsContract.coordinatesStream,
        updateIntervalMillis = InfoWidgetsContract.UPDATE_INTERVAL_MILLIS,
    )

internal fun gpsWidget(): WidgetDescriptor =
    WidgetDescriptor(
        id = WidgetId(InfoWidgetsContract.GPS_STREAM_ID),
        title = InfoWidgetsContract.GPS_TITLE,
        valueType = InfoWidgetsContract.GPS_VALUE_TYPE,
        streamId = InfoWidgetsContract.gpsStream,
        updateIntervalMillis = InfoWidgetsContract.UPDATE_INTERVAL_MILLIS,
    )
