package nl.r1a.grassmap.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.host.StreamAccess
import nl.r1a.grassmap.plugin.api.widget.AltitudeWidgetValue
import nl.r1a.grassmap.plugin.api.widget.CoordinatesWidgetValue
import nl.r1a.grassmap.plugin.api.widget.GpsInfoWidgetValue
import nl.r1a.grassmap.plugin.api.widget.SpeedWidgetValue
import nl.r1a.grassmap.plugins.info.widgets.InfoWidgetsContract
import nl.r1a.grassmap.plugins.point.info.PointInfoContract
import nl.r1a.grassmap.plugins.point.info.SelectedPointValue
import nl.r1a.grassmap.ui.shell.GridWidget
import nl.r1a.grassmap.ui.shell.WidgetGrid
import nl.r1a.grassmap.ui.shell.WidgetLayout

/**
 * The info widgets at the top of the map (T-0605). It draws the layout of the
 * active profile and sends every edit to that profile's
 * [WidgetLayoutController]. The grid cannot be edited until the stored layout
 * was read. The active profile is read when the grid enters the composition: a
 * trip activates its profile, and the grid is not drawn during a trip.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun WidgetGridHost(
    container: AppContainer,
    isEditing: Boolean,
    modifier: Modifier = Modifier,
) {
    val profile = remember(container) { container.alertSettings.activeProfileId() }
    val controller = remember(container, profile) { container.widgetLayoutController(profile) }
    LaunchedEffect(controller) { controller.load() }
    val state by controller.state.collectAsState()
    val layout = state.layout
    val readings = rememberWidgetReadings(container, layout)
    val strings = container.strings
    val showsEdit = isEditing && state.isEditable
    val cells = remember(layout, readings, showsEdit, strings) { widgetCells(layout, readings, showsEdit, strings) }
    val hidden = remember(layout, strings) { widgetOptions(layout, strings) }
    WidgetGrid(
        cells = cells,
        hidden = hidden,
        isEditing = showsEdit,
        labels = remember(strings) { widgetGridLabels(strings) },
        onAdd = { widget -> controller.edit { it.add(widget) } },
        onRemove = { widget -> controller.edit { it.remove(widget) } },
        onMove = { widget, delta -> controller.edit { it.move(widget, delta) } },
        onClear = { widget -> if (widget == GridWidget.SelectedPoint) container.selectedPoint.clear() },
        modifier = modifier,
    )
}

/** The latest value of each stream whose widget [layout] shows. The other streams are not subscribed. */
@Composable
private fun rememberWidgetReadings(
    container: AppContainer,
    layout: WidgetLayout,
): WidgetReadings {
    val access = container.streamAccess
    val altitude by shownStream<AltitudeWidgetValue>(access, layout, GridWidget.Altitude, InfoWidgetsContract.altitudeStream)
    val gps by shownStream<GpsInfoWidgetValue>(access, layout, GridWidget.GpsAccuracy, InfoWidgetsContract.gpsStream)
    val coordinates by shownStream<CoordinatesWidgetValue>(
        access,
        layout,
        GridWidget.Coordinates,
        InfoWidgetsContract.coordinatesStream,
    )
    val current by shownStream<SpeedWidgetValue>(access, layout, GridWidget.SpeedCurrent, InfoWidgetsContract.speedCurrentStream)
    val average by shownStream<SpeedWidgetValue>(access, layout, GridWidget.SpeedAverage, InfoWidgetsContract.speedAverageStream)
    val max by shownStream<SpeedWidgetValue>(access, layout, GridWidget.SpeedMax, InfoWidgetsContract.speedMaxStream)
    val selected by shownStream<SelectedPointValue>(access, layout, GridWidget.SelectedPoint, PointInfoContract.selectionStream)
    return WidgetReadings(altitude, gps, coordinates, current, average, max, selected ?: SelectedPointValue())
}

@Composable
private inline fun <reified T : Any> shownStream(
    access: StreamAccess,
    layout: WidgetLayout,
    widget: GridWidget,
    stream: StreamId,
): State<T?> {
    val isShown = widget in layout.widgets
    val flow: Flow<T?> = remember(access, isShown) { if (isShown) access.subscribe<T>(stream) else flowOf(null) }
    return flow.collectAsState(initial = null)
}
