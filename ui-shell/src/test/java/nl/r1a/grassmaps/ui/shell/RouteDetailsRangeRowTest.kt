package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.ui.api.ElevationChartPoint
import nl.r1a.grassmaps.plugin.ui.api.ElevationChartRange
import nl.r1a.grassmaps.plugin.ui.api.ElevationChartState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Round-4 design-aware P1 3, first slice: the route details carry the canon's
 * selected-range row with a truthful Clear action. The JVM suite cannot render
 * the sheet, so the composition is pinned in source and the row's values are
 * checked against the engine samples the chart drew.
 */
class RouteDetailsRangeRowTest {
    @Test
    fun clearingTheRangeLeavesTheChartAndHeaderUnselected() {
        val panel = routePanel()

        assertTrue(
            "an absent range must not become a whole-track selection",
            "selectedRange?.let { elevationRangeStats(chart, it) }" in panel,
        )
        assertFalse(
            "the old whole-track fallback would keep a band after Clear",
            "elevationRangeStats(chart, selectedRange)" in panel,
        )
        assertTrue("Clear resets the local range", "onClearRange = { selectedRange = null }" in panel)
    }

    @Test
    fun theDetailsChartPassesItsRangeStatisticsAndClearActionToTheChart() {
        val call = detailsCall()

        assertTrue("the row reads the selection the chart drew", "state = detailsChart" in call)
        assertTrue("Clear is wired to the chart", "onClearRange = { selectedRange = null }" in call)
        assertTrue("the range callback is unchanged", "onRange =" in call)
    }

    @Test
    fun theChartDrawsTheRangeRowAboveTheStrips() {
        val chart = chartSource()
        assertTrue("ElevationChart composes the range row", "ElevationRangeRow(" in chart)
        assertTrue("Clear calls the supplied action", "onClear = onClearRange" in chart)
        assertTrue("the row uses the chart's own range statistics", "trackAnalysisRangeRow(state, strings, locale, units)" in chart)
        assertTrue(
            "the row sits after the legend and before the strips",
            chart.indexOf("ElevationChartScaleLegend(") < chart.indexOf("ElevationRangeRow(") &&
                chart.indexOf("ElevationRangeRow(") < chart.indexOf("elevationStripRows("),
        )
        assertFalse("no colour selector was added to route details", "showColour = true" in routePanel())
    }

    @Test
    fun theRangeRowClearKeepsIts48DpTarget() {
        val row = chartSource().substringAfter("private fun ElevationRangeRow(").substringBefore("\n}\n")

        assertTrue(
            "Clear has no 48 dp minimum in both dimensions",
            ".sizeIn(minWidth = MIN_TAP_TARGET_DP.dp, minHeight = MIN_TAP_TARGET_DP.dp)" in row,
        )
        assertTrue("Clear lost its command", "Role.Button, onClick = onClear" in row)
        assertTrue("the range row lost its spoken label", "contentDescription = row.clearLabel" in row)
    }

    @Test
    fun theRangeRowNamesOnlyValuesDerivedFromTheChartsSamples() {
        val state =
            ElevationChartState(
                points =
                    listOf(
                        ElevationChartPoint(distanceMeters = 0.0, elevationMeters = 280.0),
                        ElevationChartPoint(distanceMeters = 900.0, elevationMeters = 342.0),
                        ElevationChartPoint(distanceMeters = 1_200.0, elevationMeters = 327.0),
                    ),
                selection =
                    elevationRangeStats(
                        ElevationChartState(
                            points =
                                listOf(
                                    ElevationChartPoint(distanceMeters = 0.0, elevationMeters = 280.0),
                                    ElevationChartPoint(distanceMeters = 900.0, elevationMeters = 342.0),
                                    ElevationChartPoint(distanceMeters = 1_200.0, elevationMeters = 327.0),
                                ),
                        ),
                        ElevationChartRange(startMeters = 0.0, endMeters = 900.0),
                    ),
            )

        val row = trackAnalysisRangeRow(state, strings(), Locale.US, UnitSystem.Metric)

        assertEquals("0-900 m · ↑62 m", row?.text)
        assertEquals("Clear", row?.clearLabel)
    }

    @Test
    fun bothLocalesCarryTheRangeRowCopy() {
        assertTrue(
            "Clear is missing in English",
            """name="elevation_chart_range_clear">Clear<""" in values("values"),
        )
        assertTrue(
            "Clear is missing in Ukrainian",
            """name="elevation_chart_range_clear">Очистити<""" in values("values-uk"),
        )
    }

    private fun routePanel(): String =
        File("src/main/java/nl/r1a/grassmaps/ui/shell/RoutePlanPanel.kt")
            .readText()
            .replace(Regex("\\s+"), " ")

    private fun chartSource(): String = File("src/main/java/nl/r1a/grassmaps/ui/shell/ElevationChart.kt").readText()

    private fun detailsCall(): String =
        File("src/main/java/nl/r1a/grassmaps/ui/shell/RoutePlanPanel.kt")
            .readText()
            .substringAfter("if (detailsExpanded) {")
            .substringAfter("ElevationChart(")
            .substringBefore("\n                    )")

    private fun values(dir: String): String = File("src/main/res/$dir/strings.xml").readText()

    private fun strings(): Strings =
        object : Strings {
            override fun resolve(
                key: String,
                arguments: List<String>,
            ): String {
                val template =
                    mapOf(
                        "unit_distance_kilometres" to "%1\$s km",
                        "unit_elevation_metres" to "%1\$s m",
                        "elevation_chart_range_kilometres" to "%1\$s-%2\$s km",
                        "elevation_chart_range_metres" to "%1\$s-%2\$s m",
                        "elevation_chart_range_ascent" to "\u2191%1\$s m",
                        "elevation_chart_range_clear" to "Clear",
                    ).getValue(key)
                return if (arguments.isEmpty()) template else String.format(Locale.ROOT, template, *arguments.toTypedArray())
            }

            override fun resolveQuantity(
                key: String,
                quantity: Int,
                arguments: List<String>,
            ): String = resolve(key, arguments)

            override fun contains(key: String): Boolean =
                key in
                    setOf(
                        "unit_distance_kilometres",
                        "unit_elevation_metres",
                        "elevation_chart_range_kilometres",
                        "elevation_chart_range_metres",
                        "elevation_chart_range_ascent",
                        "elevation_chart_range_clear",
                    )
        }
}
