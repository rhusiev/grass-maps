package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.api.widget.AltitudeSource
import nl.r1a.grassmap.plugin.api.widget.AltitudeWidgetValue
import nl.r1a.grassmap.plugin.api.widget.CoordinatesWidgetValue
import nl.r1a.grassmap.plugin.api.widget.GpsInfoWidgetValue
import nl.r1a.grassmap.plugin.api.widget.SpeedWidgetValue
import nl.r1a.grassmap.plugins.point.info.SelectedPointValue
import nl.r1a.grassmap.ui.shell.GridWidget
import nl.r1a.grassmap.ui.shell.WidgetLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class WidgetGridMappingTest {
    private val locale = Locale.US
    private val readings =
        WidgetReadings(
            altitude = AltitudeWidgetValue(296.4, AltitudeSource.Dem),
            gps = GpsInfoWidgetValue(4.2, null, null, null),
            coordinates = CoordinatesWidgetValue(GeoPoint(49.84, 24.03), GeoPoint(49.1, 24.2)),
            speedCurrent = SpeedWidgetValue(3.0),
            speedAverage = SpeedWidgetValue(1.5),
            speedMax = SpeedWidgetValue(null),
            selectedPoint = SelectedPointValue(GeoPoint(49.0, 24.0), 120.0, 45.0),
        )

    private fun cells(
        layout: WidgetLayout,
        isEditing: Boolean = false,
        value: WidgetReadings = readings,
    ) = widgetCells(layout, value, isEditing, KeyStrings, locale)

    @Test
    fun cellsFollowTheLayoutOrder() {
        val layout = WidgetLayout(listOf(GridWidget.SpeedCurrent, GridWidget.Altitude))
        assertEquals(listOf(GridWidget.SpeedCurrent, GridWidget.Altitude), cells(layout).map { it.widget })
    }

    @Test
    fun altitudeAndAccuracyAreWholeMetres() {
        val cell = cells(WidgetLayout(listOf(GridWidget.Altitude, GridWidget.GpsAccuracy)))
        assertEquals(listOf("widget_grid_metres[296]", "widget_grid_accuracy[4]"), cell.map { it.lines.single().value })
    }

    @Test
    fun coordinatesShowTheFixAndTheCentreWithFiveDecimals() {
        val lines = cells(WidgetLayout(listOf(GridWidget.Coordinates))).single().lines
        assertEquals(
            listOf("widget_grid_coordinates_value[49.84000|24.03000]", "widget_grid_coordinates_value[49.10000|24.20000]"),
            lines.map { it.value },
        )
    }

    @Test
    fun speedsUseKilometresPerHourAndADashWhenMissing() {
        val layout = WidgetLayout(listOf(GridWidget.SpeedCurrent, GridWidget.SpeedAverage, GridWidget.SpeedMax))
        assertEquals(
            listOf("11 nav_screen_speed_unit", "5.4 nav_screen_speed_unit", "-"),
            cells(layout).map { it.lines.single().value },
        )
    }

    @Test
    fun missingReadingsDrawDashes() {
        val layout = WidgetLayout(listOf(GridWidget.Altitude, GridWidget.Coordinates))
        val dashes = cells(layout, value = WidgetReadings()).flatMap { it.lines }.map { it.value }
        assertEquals(listOf("-", "-", "-"), dashes)
    }

    @Test
    fun theSelectedPointCellHasRowsAndAClearActionOnlyThere() {
        val all = cells(WidgetLayout(GridWidget.entries))
        assertEquals(2, all.single { it.widget == GridWidget.SelectedPoint }.lines.size)
        assertNotNull(all.single { it.widget == GridWidget.SelectedPoint }.clearDescription)
        assertNull(all.first { it.widget == GridWidget.Altitude }.clearDescription)
    }

    @Test
    fun theSelectedPointCellIsHiddenWithoutASelectionExceptWhileEditing() {
        val empty = readings.copy(selectedPoint = SelectedPointValue())
        val layout = WidgetLayout(listOf(GridWidget.Altitude, GridWidget.SelectedPoint))
        assertEquals(listOf(GridWidget.Altitude), cells(layout, value = empty).map { it.widget })
        assertEquals(listOf(GridWidget.Altitude, GridWidget.SelectedPoint), cells(layout, true, empty).map { it.widget })
    }

    @Test
    fun optionsListTheWidgetsThatAreNotShown() {
        val layout = WidgetLayout(GridWidget.entries - GridWidget.SpeedMax)
        assertEquals(listOf(GridWidget.SpeedMax), widgetOptions(layout, KeyStrings).map { it.widget })
    }

    private object KeyStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = if (arguments.isEmpty()) key else "$key[${arguments.joinToString("|")}]"

        override fun contains(key: String): Boolean = false
    }
}
