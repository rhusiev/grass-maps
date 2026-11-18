package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.api.widget.AltitudeWidgetValue
import nl.r1a.grassmap.plugin.api.widget.CoordinatesWidgetValue
import nl.r1a.grassmap.plugin.api.widget.GpsInfoWidgetValue
import nl.r1a.grassmap.plugin.api.widget.SpeedWidgetValue
import nl.r1a.grassmap.plugins.point.info.PointInfoLabel
import nl.r1a.grassmap.plugins.point.info.SelectedPointValue
import nl.r1a.grassmap.ui.shell.GridWidget
import nl.r1a.grassmap.ui.shell.NavigationScreenStrings
import nl.r1a.grassmap.ui.shell.WidgetCell
import nl.r1a.grassmap.ui.shell.WidgetGridLabels
import nl.r1a.grassmap.ui.shell.WidgetGridStrings
import nl.r1a.grassmap.ui.shell.WidgetLayout
import nl.r1a.grassmap.ui.shell.WidgetLine
import nl.r1a.grassmap.ui.shell.WidgetOption
import java.util.Locale

/** The latest value of every stream the grid shows. A null stream value means no reading yet. */
data class WidgetReadings(
    val altitude: AltitudeWidgetValue? = null,
    val gps: GpsInfoWidgetValue? = null,
    val coordinates: CoordinatesWidgetValue? = null,
    val speedCurrent: SpeedWidgetValue? = null,
    val speedAverage: SpeedWidgetValue? = null,
    val speedMax: SpeedWidgetValue? = null,
    val selectedPoint: SelectedPointValue = SelectedPointValue(),
)

private const val MISSING_VALUE = "-"
private const val COORDINATE_DECIMALS = 5

/**
 * The cells of [layout] in order. The selected-point cell is left out while
 * nothing is selected, unless [isEditing] needs it as a handle to move or remove.
 */
internal fun widgetCells(
    layout: WidgetLayout,
    readings: WidgetReadings,
    isEditing: Boolean,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
): List<WidgetCell> =
    layout.widgets
        .map { widget -> widgetCell(widget, readings, strings, locale) }
        .filter { cell -> isEditing || cell.lines.isNotEmpty() }

/** The widgets that are not in [layout], as the picker lists them. */
internal fun widgetOptions(
    layout: WidgetLayout,
    strings: Strings,
): List<WidgetOption> = layout.hidden.map { widget -> WidgetOption(widget, widgetTitle(widget, strings)) }

/** The chrome text of the grid in edit mode. */
internal fun widgetGridLabels(strings: Strings): WidgetGridLabels =
    WidgetGridLabels(
        add = strings.resolve(WidgetGridStrings.ADD),
        addDescription = strings.resolve(WidgetGridStrings.ADD_DESCRIPTION),
        notShown = strings.resolve(WidgetGridStrings.NOT_SHOWN),
        remove = { title -> strings.resolve(WidgetGridStrings.REMOVE_DESCRIPTION, listOf(title)) },
        moveEarlier = { title -> strings.resolve(WidgetGridStrings.MOVE_EARLIER_DESCRIPTION, listOf(title)) },
        moveLater = { title -> strings.resolve(WidgetGridStrings.MOVE_LATER_DESCRIPTION, listOf(title)) },
        pick = { title -> strings.resolve(WidgetGridStrings.PICK_DESCRIPTION, listOf(title)) },
    )

private fun widgetCell(
    widget: GridWidget,
    readings: WidgetReadings,
    strings: Strings,
    locale: Locale,
): WidgetCell =
    WidgetCell(
        widget = widget,
        title = widgetTitle(widget, strings),
        lines = widgetLines(widget, readings, strings, locale),
        clearDescription =
            strings
                .resolve(WidgetGridStrings.CLEAR_DESCRIPTION)
                .takeIf { widget == GridWidget.SelectedPoint },
    )

private fun widgetLines(
    widget: GridWidget,
    readings: WidgetReadings,
    strings: Strings,
    locale: Locale,
): List<WidgetLine> =
    when (widget) {
        GridWidget.Altitude -> {
            listOf(altitudeLine(readings.altitude, strings, locale))
        }

        GridWidget.GpsAccuracy -> {
            listOf(accuracyLine(readings.gps, strings, locale))
        }

        GridWidget.Coordinates -> {
            coordinateLines(readings.coordinates, strings, locale)
        }

        GridWidget.SpeedCurrent -> {
            listOf(speedLine(readings.speedCurrent, null, strings, locale))
        }

        GridWidget.SpeedAverage -> {
            listOf(speedLine(readings.speedAverage, WidgetGridStrings.SPEED_AVERAGE_LABEL, strings, locale))
        }

        GridWidget.SpeedMax -> {
            listOf(speedLine(readings.speedMax, WidgetGridStrings.SPEED_MAX_LABEL, strings, locale))
        }

        GridWidget.SelectedPoint -> {
            PointInfoLabel.rows(readings.selectedPoint, strings).map { row -> WidgetLine(row) }
        }
    }

private fun widgetTitle(
    widget: GridWidget,
    strings: Strings,
): String =
    strings.resolve(
        when (widget) {
            GridWidget.Altitude -> WidgetGridStrings.ALTITUDE
            GridWidget.GpsAccuracy -> WidgetGridStrings.GPS_ACCURACY
            GridWidget.Coordinates -> WidgetGridStrings.COORDINATES
            GridWidget.SpeedCurrent -> WidgetGridStrings.SPEED_CURRENT
            GridWidget.SpeedAverage -> WidgetGridStrings.SPEED_AVERAGE
            GridWidget.SpeedMax -> WidgetGridStrings.SPEED_MAX
            GridWidget.SelectedPoint -> WidgetGridStrings.SELECTED_POINT
        },
    )

private fun altitudeLine(
    value: AltitudeWidgetValue?,
    strings: Strings,
    locale: Locale,
): WidgetLine {
    val metres = value?.altitudeMeters?.takeIf { it.isFinite() }
    val text =
        metres?.let { strings.resolve(WidgetGridStrings.METRES, listOf(String.format(locale, "%.0f", it))) }
    return WidgetLine(text ?: MISSING_VALUE, strings.resolve(WidgetGridStrings.ALTITUDE_LABEL))
}

private fun accuracyLine(
    value: GpsInfoWidgetValue?,
    strings: Strings,
    locale: Locale,
): WidgetLine {
    val metres = value?.accuracyMeters?.takeIf { it.isFinite() }
    val text =
        metres?.let { strings.resolve(WidgetGridStrings.ACCURACY, listOf(String.format(locale, "%.0f", it))) }
    return WidgetLine(text ?: MISSING_VALUE, strings.resolve(WidgetGridStrings.GPS_LABEL))
}

private fun coordinateLines(
    value: CoordinatesWidgetValue?,
    strings: Strings,
    locale: Locale,
): List<WidgetLine> =
    listOf(
        WidgetLine(coordinateText(value?.fix, strings, locale), strings.resolve(WidgetGridStrings.FIX_LABEL)),
        WidgetLine(coordinateText(value?.mapCenter, strings, locale), strings.resolve(WidgetGridStrings.CENTRE_LABEL)),
    )

private fun coordinateText(
    point: GeoPoint?,
    strings: Strings,
    locale: Locale,
): String =
    point?.let {
        strings.resolve(
            WidgetGridStrings.COORDINATES_VALUE,
            listOf(
                String.format(locale, "%.${COORDINATE_DECIMALS}f", it.latitude),
                String.format(locale, "%.${COORDINATE_DECIMALS}f", it.longitude),
            ),
        )
    } ?: MISSING_VALUE

private fun speedLine(
    value: SpeedWidgetValue?,
    labelKey: String?,
    strings: Strings,
    locale: Locale,
): WidgetLine {
    val kmh = formatSpeedKmh(value?.speedMetersPerSecond, locale)
    val unit = strings.resolve(NavigationScreenStrings.SPEED_UNIT)
    return WidgetLine(
        value = kmh?.let { "$it $unit" } ?: MISSING_VALUE,
        label = labelKey?.let { strings.resolve(it) }.orEmpty(),
    )
}
