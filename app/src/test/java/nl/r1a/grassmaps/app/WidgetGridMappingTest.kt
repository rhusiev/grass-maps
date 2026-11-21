package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.api.widget.AltitudeSource
import nl.r1a.grassmaps.plugin.api.widget.AltitudeWidgetValue
import nl.r1a.grassmaps.plugin.api.widget.CoordinatesWidgetValue
import nl.r1a.grassmaps.plugin.api.widget.GpsInfoWidgetValue
import nl.r1a.grassmaps.plugin.api.widget.SpeedWidgetValue
import nl.r1a.grassmaps.plugins.point.info.SelectedPointValue
import nl.r1a.grassmaps.ui.shell.GridWidget
import nl.r1a.grassmaps.ui.shell.WidgetLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        units: UnitSystem = UnitSystem.Metric,
    ) = widgetCells(layout, value, isEditing, KeyStrings, locale, units)

    @Test
    fun cellsFollowTheLayoutOrder() {
        val layout = WidgetLayout(listOf(GridWidget.SpeedCurrent, GridWidget.Altitude))
        assertEquals(listOf(GridWidget.SpeedCurrent, GridWidget.Altitude), cells(layout).map { it.widget })
    }

    @Test
    fun altitudeAndAccuracyAreWholeMetres() {
        val cell = cells(WidgetLayout(listOf(GridWidget.Altitude, GridWidget.GpsAccuracy)))
        assertEquals(listOf("296 m", "±4 m"), cell.map { it.lines.single().value })
    }

    @Test
    fun altitudeAccuracySpeedAndTheSelectedPointFollowImperial() {
        val layout = WidgetLayout(GridWidget.entries)
        val lines = cells(layout, units = UnitSystem.Imperial).flatMap { it.lines }.map { it.value }

        assertTrue(lines.contains("972 ft"))
        assertTrue(lines.contains("±14 ft"))
        assertTrue(lines.contains("6.7 mph"))
        assertTrue(lines.contains("3.4 mph"))
        assertTrue(lines.contains("Distance 394 ft"))
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
            listOf("11 km/h", "5.4 km/h", "-"),
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

    private object KeyStrings : Strings {
        private val values =
            mapOf(
                "unit_distance_metres" to "%1\$s m",
                "unit_distance_kilometres" to "%1\$s km",
                "unit_distance_feet" to "%1\$s ft",
                "unit_distance_miles" to "%1\$s mi",
                "unit_elevation_metres" to "%1\$s m",
                "unit_elevation_feet" to "%1\$s ft",
                "unit_speed_kilometres_per_hour" to "km/h",
                "unit_speed_miles_per_hour" to "mph",
                "widget_grid_accuracy" to "±%1\$s",
                "point_info_distance_row" to "Distance %1\$s",
                "point_info_azimuth_row" to "Azimuth %1\$s",
                "point_info_azimuth_degrees" to "%1\$s°",
            )

        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String {
            val template = values[key] ?: return if (arguments.isEmpty()) key else "$key[${arguments.joinToString("|")}]"
            return if (arguments.isEmpty()) template else String.format(Locale.US, template, *arguments.toTypedArray())
        }

        override fun contains(key: String): Boolean = key in values
    }
}
