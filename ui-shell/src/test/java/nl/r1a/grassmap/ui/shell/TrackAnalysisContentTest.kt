package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** The track analysis panel's labels and its meta line (T-0404). */
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
