package nl.r1a.grassmap.plugins.info.widgets

import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.contract.WidgetId
import nl.r1a.grassmap.plugin.api.widget.WidgetCadence
import nl.r1a.grassmap.plugin.api.widget.WidgetDescriptor

/**
 * The ids, titles and value types the plugin declares in `plugin.json`.
 *
 * They live here as constants so the activation code, the tests and the
 * control-channel evidence all name the same widget. `InfoWidgetsManifestTest`
 * checks the manifest against them, and `InfoWidgetsPluginTest` checks the
 * value types against the serializers.
 */
object InfoWidgetsContract {
    const val PLUGIN_ID: String = "info.widgets"
    const val ALTITUDE_STREAM_ID: String = "info.widgets.altitude"
    const val COORDINATES_STREAM_ID: String = "info.widgets.coordinates"
    const val GPS_STREAM_ID: String = "info.widgets.gps"
    const val SPEED_CURRENT_STREAM_ID: String = "info.widgets.speed.current"
    const val SPEED_AVERAGE_STREAM_ID: String = "info.widgets.speed.average"
    const val SPEED_MAX_STREAM_ID: String = "info.widgets.speed.max"

    const val ALTITUDE_TITLE: String = "plugin.info_widgets.altitude"
    const val COORDINATES_TITLE: String = "plugin.info_widgets.coordinates"
    const val GPS_TITLE: String = "plugin.info_widgets.gps"
    const val SPEED_CURRENT_TITLE: String = "plugin.info_widgets.speed_current"
    const val SPEED_AVERAGE_TITLE: String = "plugin.info_widgets.speed_average"
    const val SPEED_MAX_TITLE: String = "plugin.info_widgets.speed_max"

    const val ALTITUDE_VALUE_TYPE: String = "nl.r1a.grassmap.plugin.api.widget.AltitudeWidgetValue"
    const val COORDINATES_VALUE_TYPE: String = "nl.r1a.grassmap.plugin.api.widget.CoordinatesWidgetValue"
    const val GPS_VALUE_TYPE: String = "nl.r1a.grassmap.plugin.api.widget.GpsInfoWidgetValue"
    const val SPEED_VALUE_TYPE: String = "nl.r1a.grassmap.plugin.api.widget.SpeedWidgetValue"

    const val UPDATE_INTERVAL_MILLIS: Long = WidgetCadence.DEFAULT_INTERVAL_MILLIS

    /**
     * Keep a shared source alive briefly after its last subscriber goes away, so
     * a widget that is re-bound after a screen rebuild does not restart the
     * replay or lose the max speed.
     */
    internal const val SHARE_STOP_MILLIS: Long = 5_000L

    val altitudeStream: StreamId = StreamId(ALTITUDE_STREAM_ID)
    val coordinatesStream: StreamId = StreamId(COORDINATES_STREAM_ID)
    val gpsStream: StreamId = StreamId(GPS_STREAM_ID)
    val speedCurrentStream: StreamId = StreamId(SPEED_CURRENT_STREAM_ID)
    val speedAverageStream: StreamId = StreamId(SPEED_AVERAGE_STREAM_ID)
    val speedMaxStream: StreamId = StreamId(SPEED_MAX_STREAM_ID)
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

internal fun speedCurrentWidget(): WidgetDescriptor =
    WidgetDescriptor(
        id = WidgetId(InfoWidgetsContract.SPEED_CURRENT_STREAM_ID),
        title = InfoWidgetsContract.SPEED_CURRENT_TITLE,
        valueType = InfoWidgetsContract.SPEED_VALUE_TYPE,
        streamId = InfoWidgetsContract.speedCurrentStream,
        updateIntervalMillis = InfoWidgetsContract.UPDATE_INTERVAL_MILLIS,
    )

internal fun speedAverageWidget(): WidgetDescriptor =
    WidgetDescriptor(
        id = WidgetId(InfoWidgetsContract.SPEED_AVERAGE_STREAM_ID),
        title = InfoWidgetsContract.SPEED_AVERAGE_TITLE,
        valueType = InfoWidgetsContract.SPEED_VALUE_TYPE,
        streamId = InfoWidgetsContract.speedAverageStream,
        updateIntervalMillis = InfoWidgetsContract.UPDATE_INTERVAL_MILLIS,
    )

internal fun speedMaxWidget(): WidgetDescriptor =
    WidgetDescriptor(
        id = WidgetId(InfoWidgetsContract.SPEED_MAX_STREAM_ID),
        title = InfoWidgetsContract.SPEED_MAX_TITLE,
        valueType = InfoWidgetsContract.SPEED_VALUE_TYPE,
        streamId = InfoWidgetsContract.speedMaxStream,
        updateIntervalMillis = InfoWidgetsContract.UPDATE_INTERVAL_MILLIS,
    )
