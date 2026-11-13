package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.ElevationChartClimb
import nl.r1a.grassmap.plugin.ui.api.ElevationChartRange
import nl.r1a.grassmap.plugin.ui.api.ElevationChartRangeStats
import nl.r1a.grassmap.plugin.ui.api.ElevationChartState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The track analysis panel's labels, its meta line, and its range and climb rows (T-0404, T-0405). */
class TrackAnalysisContentTest {
    @Test
    fun theTilesNameTheFourStatisticsAndFormatTheEngineValues() {
        val display = display(SUMMARY)

        assertEquals("Znesinnia ridges", display.title)
        assertEquals(listOf("Distance", "Ascent", "Moving", "Speed"), display.tiles.map { it.label })
        assertEquals(listOf("7.5 km", "219 m", "2 h 6 min", "3.6 km/h"), display.tiles.map { it.value })
    }

    @Test
    fun theMetaLineCarriesTotalStoppedMaximumAndDescent() {
        val display = display(SUMMARY)

        assertEquals("Total 2 h 41 min · stopped 35 min · max 6.2 km/h · ↓219 m", display.meta)
    }

    @Test
    fun aTrackWithoutTimingKeepsTheTilesAndDropsTheTimeParts() {
        val summary = SUMMARY.copy(movingMillis = null, durationMillis = null, maxSpeedMetersPerSecond = null)

        val display = display(summary)

        assertEquals("-", display.tiles[2].value)
        assertEquals("↓219 m", display.meta)
    }

    @Test
    fun anUnnamedTrackFallsBackToTheTrackLabel() {
        assertEquals("Track", display(SUMMARY.copy(name = null)).title)
    }

    @Test
    fun theDetailsLabelFollowsTheToggle() {
        assertEquals("Show details", display(SUMMARY, expanded = false).detailsLabel)
        assertEquals("Hide details", display(SUMMARY, expanded = true).detailsLabel)
    }

    @Test
    fun thePanelLabelsResolveInUkrainian() {
        val display =
            trackAnalysisDisplay(SUMMARY, MapStrings(UKRAINIAN), UKRAINIAN_LOCALE, expanded = false)

        assertEquals(listOf("Відстань", "Підйом", "У русі", "Швидкість"), display.tiles.map { it.label })
        assertEquals("3,6 км/год", display.tiles[3].value)
        assertEquals("Показати подробиці", display.detailsLabel)
    }

    @Test
    fun aSummaryWithNothingMeasuredHasNoMetaLine() {
        val display =
            trackAnalysisDisplay(
                TrackAnalysisSummary(name = "Empty"),
                MapStrings(ENGLISH),
                Locale.ENGLISH,
            )

        assertNull(display.meta)
    }

    @Test
    fun theClimbRowsCarryTheEnginesRangesGainsGradesAndTimes() {
        val rows = trackAnalysisClimbRows(CLIMB_STATE, MapStrings(ENGLISH), Locale.ENGLISH)

        assertEquals(listOf(1, 2, 3), rows.map { it.ordinal })
        assertEquals(listOf("0.3-1.1 km", "2.1-3.5 km", "5.6-6.4 km"), rows.map { it.title })
        assertEquals(listOf("↑105 m", "↑64 m", "↑32 m"), rows.map { it.ascent })
        assertEquals(listOf("13%", "5%", "4%"), rows.map { it.grade })
        assertEquals(listOf("18 min", "25 min", "14 min"), rows.map { it.duration })
        assertEquals(listOf(5, 4, 4), rows.map { it.badgeBin })
        assertEquals(
            listOf(
                ElevationChartRange(300.0, 1_100.0),
                ElevationChartRange(2_100.0, 3_500.0),
                ElevationChartRange(5_600.0, 6_400.0),
            ),
            rows.map { it.range },
        )
    }

    @Test
    fun tappingAClimbUsesTheRangeTheEngineFound() {
        val rows = trackAnalysisClimbRows(CLIMB_STATE, MapStrings(ENGLISH), Locale.ENGLISH)

        assertEquals(ElevationChartRange(300.0, 1_100.0), rows[0].range)
        assertEquals(ElevationChartRange(2_100.0, 3_500.0), rows[1].range)
    }

    @Test
    fun onlyTheSelectedClimbsRowIsMarked() {
        val rows = trackAnalysisClimbRows(CLIMB_STATE, MapStrings(ENGLISH), Locale.ENGLISH)

        assertEquals(listOf(true, false, false), rows.map { it.selected })
    }

    @Test
    fun aClimbWithoutTimingKeepsItsRowAndDropsTheTime() {
        val state = ElevationChartState(climbs = listOf(CLIMBS.first().copy(durationMillis = null)))

        val rows = trackAnalysisClimbRows(state, MapStrings(ENGLISH), Locale.ENGLISH)

        assertNull(rows[0].duration)
    }

    @Test
    fun aChartWithoutClimbsHasNoRows() {
        assertTrue(trackAnalysisClimbRows(ElevationChartState(), MapStrings(ENGLISH), Locale.ENGLISH).isEmpty())
    }

    @Test
    fun theRangeRowCarriesTheEnginesDistanceGainDescentAndTime() {
        val state =
            ElevationChartState(
                selection =
                    ELEVATION_SELECTION.copy(
                        descentMeters = 12.0,
                    ),
            )

        val row = trackAnalysisRangeRow(state, MapStrings(ENGLISH), Locale.ENGLISH)

        assertEquals("0.3-1.1 km · ↑105 m · ↓12 m · 18 min", row?.text)
        assertEquals("Clear", row?.clearLabel)
    }

    @Test
    fun aClimbRangeLeavesOutTheDescentItDoesNotHave() {
        val row = trackAnalysisRangeRow(ELEVATION_STATE, MapStrings(ENGLISH), Locale.ENGLISH)

        assertEquals("0.3-1.1 km · ↑105 m · 18 min", row?.text)
    }

    @Test
    fun aRangeUnderAKilometreReadsInMetres() {
        val state =
            ElevationChartState(
                selection =
                    ELEVATION_SELECTION.copy(
                        range = ElevationChartRange(120.0, 880.0),
                        distanceMeters = 760.0,
                        durationMillis = null,
                    ),
            )

        val row = trackAnalysisRangeRow(state, MapStrings(ENGLISH), Locale.ENGLISH)

        assertEquals("120-880 m · ↑105 m", row?.text)
    }

    @Test
    fun anUnselectedChartHasNoRangeRow() {
        assertNull(trackAnalysisRangeRow(ElevationChartState(), MapStrings(ENGLISH), Locale.ENGLISH))
    }

    @Test
    fun theRowsResolveInUkrainian() {
        val strings = MapStrings(UKRAINIAN)
        val row = trackAnalysisRangeRow(ELEVATION_STATE, strings, UKRAINIAN_LOCALE)
        val climbs = trackAnalysisClimbRows(CLIMB_STATE, strings, UKRAINIAN_LOCALE)

        assertEquals("0,3-1,1 км · ↑105 м · 18 хв", row?.text)
        assertEquals("Очистити", row?.clearLabel)
        assertEquals("0,3-1,1 км", climbs[0].title)
        assertEquals("↑105 м", climbs[0].ascent)
        assertEquals("13%", climbs[0].grade)
        assertEquals("18 хв", climbs[0].duration)
    }

    private fun display(
        summary: TrackAnalysisSummary,
        expanded: Boolean = false,
    ): TrackAnalysisDisplay = trackAnalysisDisplay(summary, MapStrings(ENGLISH), Locale.ENGLISH, expanded)

    private class MapStrings(
        private val values: Map<String, String>,
    ) : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String {
            val template = values[key] ?: key
            return if (arguments.isEmpty()) template else String.format(Locale.ROOT, template, *arguments.toTypedArray())
        }

        override fun contains(key: String): Boolean = key in values
    }

    private companion object {
        val UKRAINIAN_LOCALE: Locale = Locale.forLanguageTag("uk")

        val SUMMARY =
            TrackAnalysisSummary(
                name = "Znesinnia ridges",
                distanceMeters = 7_500.0,
                ascentMeters = 219.0,
                descentMeters = 219.0,
                movingMillis = 7_560_000L,
                durationMillis = 9_660_000L,
                averageSpeedMetersPerSecond = 3.6,
                maxSpeedMetersPerSecond = 6.2,
            )

        val CLIMBS =
            listOf(
                ElevationChartClimb(300.0, 1_100.0, 105.0, 13.125, 1_080_000L),
                ElevationChartClimb(2_100.0, 3_500.0, 64.0, 4.57, 1_500_000L),
                ElevationChartClimb(5_600.0, 6_400.0, 32.0, 4.0, 840_000L),
            )

        val ELEVATION_SELECTION =
            ElevationChartRangeStats(
                range = ElevationChartRange(300.0, 1_100.0),
                distanceMeters = 800.0,
                ascentMeters = 105.0,
                descentMeters = 0.0,
                averageGradePercent = 13.125,
                durationMillis = 1_080_000L,
            )

        val ELEVATION_STATE = ElevationChartState(selection = ELEVATION_SELECTION)

        val CLIMB_STATE = ElevationChartState(selection = ELEVATION_SELECTION, climbs = CLIMBS)

        val ENGLISH =
            mapOf(
                "track_analysis_distance" to "Distance",
                "track_analysis_ascent" to "Ascent",
                "track_analysis_moving" to "Moving",
                "track_analysis_speed" to "Speed",
                "track_analysis_speed_value" to "%1\$s km/h",
                "track_analysis_metres" to "%1\$s m",
                "track_analysis_total" to "Total %1\$s",
                "track_analysis_stopped" to "stopped %1\$s",
                "track_analysis_max_speed" to "max %1\$s",
                "track_analysis_descent" to "↓%1\$s m",
                "track_analysis_untitled" to "Track",
                "track_analysis_close" to "Close the analysis",
                "elevation_chart_details_show" to "Show details",
                "elevation_chart_details_hide" to "Hide details",
                "elevation_chart_range_kilometres" to "%1\$s-%2\$s km",
                "elevation_chart_range_metres" to "%1\$s-%2\$s m",
                "elevation_chart_range_ascent" to "↑%1\$s m",
                "elevation_chart_range_descent" to "↓%1\$s m",
                "elevation_chart_range_grade" to "%1\$s%%",
                "elevation_chart_range_clear" to "Clear",
                "elevation_chart_climbs_title" to "Climbs · tap one to select it",
                "elevation_chart_minutes" to "%1\$s min",
                "elevation_chart_hours" to "%1\$s h",
                "elevation_chart_hours_minutes" to "%1\$s h %2\$s min",
                "route_preview_metres" to "%1\$s m",
                "route_preview_kilometres" to "%1\$s km",
                "route_preview_minutes" to "%1\$s min",
                "route_preview_hours" to "%1\$s h",
                "route_preview_hours_minutes" to "%1\$s h %2\$s min",
            )

        val UKRAINIAN =
            mapOf(
                "track_analysis_distance" to "Відстань",
                "track_analysis_ascent" to "Підйом",
                "track_analysis_moving" to "У русі",
                "track_analysis_speed" to "Швидкість",
                "track_analysis_speed_value" to "%1\$s км/год",
                "track_analysis_metres" to "%1\$s м",
                "track_analysis_total" to "Загалом %1\$s",
                "track_analysis_stopped" to "зупинки %1\$s",
                "track_analysis_max_speed" to "макс. %1\$s",
                "track_analysis_descent" to "↓%1\$s м",
                "track_analysis_untitled" to "Трек",
                "track_analysis_close" to "Закрити аналіз",
                "elevation_chart_details_show" to "Показати подробиці",
                "elevation_chart_details_hide" to "Сховати подробиці",
                "elevation_chart_range_kilometres" to "%1\$s-%2\$s км",
                "elevation_chart_range_metres" to "%1\$s-%2\$s м",
                "elevation_chart_range_ascent" to "↑%1\$s м",
                "elevation_chart_range_descent" to "↓%1\$s м",
                "elevation_chart_range_grade" to "%1\$s%%",
                "elevation_chart_range_clear" to "Очистити",
                "elevation_chart_climbs_title" to "Підйоми · торкніться, щоб вибрати",
                "elevation_chart_metres" to "%1\$s м",
                "elevation_chart_kilometres" to "%1\$s км",
                "elevation_chart_minutes" to "%1\$s хв",
                "elevation_chart_hours" to "%1\$s год",
                "elevation_chart_hours_minutes" to "%1\$s год %2\$s хв",
                "route_preview_metres" to "%1\$s м",
                "route_preview_kilometres" to "%1\$s км",
                "route_preview_minutes" to "%1\$s хв",
                "route_preview_hours" to "%1\$s год",
                "route_preview_hours_minutes" to "%1\$s год %2\$s хв",
            )
    }
}
