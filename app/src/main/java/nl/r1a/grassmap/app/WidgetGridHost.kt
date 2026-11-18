package nl.r1a.grassmap.app

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.error.onFailure
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

private const val WIDGET_TAG = "WidgetGrid"

/**
 * The info widgets at the top of the map (T-0605). It reads the layout of the
 * active profile from the widget store, draws the grid and writes every change
 * back to that profile. The active profile is read when the grid enters the
 * composition: a trip activates its profile, and the grid is not drawn during
 * a trip.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun WidgetGridHost(
    container: AppContainer,
    isEditing: Boolean,
    modifier: Modifier = Modifier,
) {
    val profile = remember(container) { container.alertSettings.activeProfileId() }
    val store = container.widgetLayoutStore
    var layout by remember { mutableStateOf(WidgetLayout.DEFAULT) }
    val currentLayout = rememberUpdatedState(layout)
    val scope = rememberCoroutineScope()
    val writer =
        remember(store, profile) {
            LayoutWriter(
                currentLayout = { currentLayout.value },
                save = { value -> withContext(Dispatchers.IO) { store.save(profile, value) } },
            )
        }
    LaunchedEffect(store, profile) {
        layout =
            withContext(Dispatchers.IO) {
                store.load().onFailure { Log.w(WIDGET_TAG, "widget layout not read: $it") }
                store.layoutOf(profile)
            }
    }
    val readings = rememberWidgetReadings(container)
    val strings = container.strings
    val change: (WidgetLayout) -> Unit = { next ->
        layout = next
        scope.launch { writer.saveLatest().onFailure { Log.w(WIDGET_TAG, "widget layout not saved: $it") } }
    }
    WidgetGrid(
        cells = widgetCells(layout, readings, isEditing, strings),
        hidden = widgetOptions(layout, strings),
        isEditing = isEditing,
        labels = remember(strings) { widgetGridLabels(strings) },
        onAdd = { widget -> change(layout.add(widget)) },
        onRemove = { widget -> change(layout.remove(widget)) },
        onMove = { widget, delta -> change(layout.move(widget, delta)) },
        onClear = { widget -> if (widget == GridWidget.SelectedPoint) container.selectedPoint.clear() },
        modifier = modifier,
    )
}

@Composable
private fun rememberWidgetReadings(container: AppContainer): WidgetReadings {
    val access = container.streamAccess
    val altitude by remember(container) { access.subscribe<AltitudeWidgetValue>(InfoWidgetsContract.altitudeStream) }
        .collectAsState(initial = null)
    val gps by remember(container) { access.subscribe<GpsInfoWidgetValue>(InfoWidgetsContract.gpsStream) }
        .collectAsState(initial = null)
    val coordinates by remember(container) {
        access.subscribe<CoordinatesWidgetValue>(InfoWidgetsContract.coordinatesStream)
    }.collectAsState(initial = null)
    val current by remember(container) { access.subscribe<SpeedWidgetValue>(InfoWidgetsContract.speedCurrentStream) }
        .collectAsState(initial = null)
    val average by remember(container) { access.subscribe<SpeedWidgetValue>(InfoWidgetsContract.speedAverageStream) }
        .collectAsState(initial = null)
    val max by remember(container) { access.subscribe<SpeedWidgetValue>(InfoWidgetsContract.speedMaxStream) }
        .collectAsState(initial = null)
    val selected by remember(container) { access.subscribe<SelectedPointValue>(PointInfoContract.selectionStream) }
        .collectAsState(initial = SelectedPointValue())
    return WidgetReadings(altitude, gps, coordinates, current, average, max, selected)
}
