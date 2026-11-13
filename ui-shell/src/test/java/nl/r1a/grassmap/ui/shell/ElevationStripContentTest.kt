package nl.r1a.grassmap.ui.shell

import androidx.compose.ui.graphics.Color
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.ElevationChartAxis
import nl.r1a.grassmap.plugin.ui.api.ElevationChartPoint
import nl.r1a.grassmap.plugin.ui.api.ElevationChartState
import nl.r1a.grassmap.plugin.ui.api.ElevationChartStretch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The surface and way-type strips (T-0406): the percentages are the engine's
 * summed segment lengths, the pieces sit on the chart's own axis, and the
 * classes, labels and colours are the round-10 design's.
 */
class ElevationStripContentTest {
    @Test
    fun thePercentagesAreTheSummedSegmentLengths() {
        val rows = rows(SYNTHETIC)

        val surface = row(rows, "Surface")
        assertEquals(listOf(50, 30, 20), surface.legend.map { it.percent })
        assertEquals(listOf(0.5, 0.3, 0.2), surface.legend.map { it.share })
        assertEquals(1.0, surface.legend.sumOf { it.share }, 1e-12)
    }

    @Test
    fun wholePercentagesStillAddUpToOneHundred() {
        val state =
            chart(
                ElevationChartStretch(0.0, 1_000.0, surface = "asphalt"),
                ElevationChartStretch(1_000.0, 2_000.0, surface = "gravel"),
                ElevationChartStretch(2_000.0, 3_000.0, surface = "ground"),
            )

        val surface = row(rows(state), "Surface")

        assertEquals(100, surface.legend.sumOf { it.percent })
        assertEquals(listOf(34, 33, 33), surface.legend.map { it.percent })
    }

    @Test
    fun aStretchBoundaryIsTheChartsOwnFraction() {
        val state = SYNTHETIC
        val surface = row(rows(state), "Surface")

        assertEquals(listOf(0f, 0.5f, 0.8f), surface.pieces.map { it.startFraction })
        assertEquals(listOf(0.5f, 0.8f, 1f), surface.pieces.map { it.endFraction })
        assertEquals(fractionAt(state, 2_500.0), surface.pieces[1].startFraction, TOLERANCE)
        assertEquals(fractionAt(state, 4_000.0), surface.pieces[1].endFraction, TOLERANCE)
    }

    @Test
    fun theStripsFollowTheChosenAxis() {
        val timed = TIMED_STATE
        val duration = chart(*timed.stretches.toTypedArray(), points = timed.points, axis = ElevationChartAxis.Duration)

        val surface = row(rows(duration), "Surface")

        assertEquals(0.25f, surface.pieces[1].startFraction, TOLERANCE)
        assertEquals(fractionAt(duration, 2_500.0), surface.pieces[1].startFraction, TOLERANCE)
    }

    @Test
    fun aLineWithoutAttributesHasNoStrips() {
        val state =
            chart(
                ElevationChartStretch(0.0, 2_500.0, surface = "unknown", wayType = "unknown"),
                ElevationChartStretch(2_500.0, 5_000.0),
            )

        assertTrue(rows(state).isEmpty())
    }

    @Test
    fun anUnclassifiedSurfaceStillDrawsItsWayType() {
        val state = chart(ElevationChartStretch(0.0, 5_000.0, wayType = "residential"))

        val content = rows(state)

        assertEquals(1, content.size)
        assertEquals("Way type", content[0].title)
        assertEquals(listOf(ElevationStripBand.Street), content[0].legend.map { it.band })
        assertEquals(100, content[0].legend[0].percent)
    }

    @Test
    fun anUnclassifiedPartIsLeftOutOfTheStripAndTheFraction() {
        val state =
            chart(
                ElevationChartStretch(0.0, 2_000.0, surface = "asphalt"),
                ElevationChartStretch(2_000.0, 3_000.0, surface = "unknown"),
                ElevationChartStretch(3_000.0, 5_000.0, surface = "gravel"),
            )

        val surface = row(rows(state), "Surface")

        assertEquals(listOf(50, 50), surface.legend.map { it.percent })
        assertEquals(2, surface.pieces.size)
        assertEquals(0.6f, surface.pieces[1].startFraction, TOLERANCE)
    }

    @Test
    fun adjacentStretchesOfOneClassMakeOnePiece() {
        val state =
            chart(
                ElevationChartStretch(0.0, 1_000.0, surface = "asphalt"),
                ElevationChartStretch(1_000.0, 2_500.0, surface = "paved"),
                ElevationChartStretch(2_500.0, 5_000.0, surface = "gravel"),
            )

        val surface = row(rows(state), "Surface")

        assertEquals(2, surface.pieces.size)
        assertEquals(0f, surface.pieces[0].startFraction)
        assertEquals(0.5f, surface.pieces[0].endFraction)
        assertEquals(listOf(ElevationStripBand.Asphalt, ElevationStripBand.Gravel), surface.legend.map { it.band })
        assertEquals(listOf(50, 50), surface.legend.map { it.percent })
    }

    @Test
    fun stepsAreStripedAndTheWayTypeUsesItsPalette() {
        val state =
            chart(
                ElevationChartStretch(0.0, 4_500.0, wayType = "residential"),
                ElevationChartStretch(4_500.0, 5_000.0, wayType = "steps"),
            )

        val wayType = row(rows(state), "Way type")

        assertEquals(listOf(false, true), wayType.pieces.map { it.striped })
        assertEquals(ElevationStripBand.Steps, wayType.pieces[1].band)
        assertEquals(Color(0xFF202124), elevationStripStripeColour(dark = false))
        assertEquals(Color(0xFFE8EAED), elevationStripStripeColour(dark = true))
    }

    @Test
    fun theLegendKeepsTheClassesInDistanceOrder() {
        val state =
            chart(
                ElevationChartStretch(0.0, 1_000.0, wayType = "residential"),
                ElevationChartStretch(1_000.0, 2_000.0, wayType = "steps"),
                ElevationChartStretch(2_000.0, 3_400.0, wayType = "path"),
                ElevationChartStretch(3_400.0, 5_000.0, wayType = "residential"),
            )

        val wayType = row(rows(state), "Way type")

        assertEquals(
            listOf(ElevationStripBand.Street, ElevationStripBand.Steps, ElevationStripBand.Path),
            wayType.legend.map { it.band },
        )
        assertEquals(listOf("Street", "Steps", "Path"), wayType.legend.map { it.label })
        assertEquals(listOf(52, 20, 28), wayType.legend.map { it.percent })
    }

    @Test
    fun theRowsAndTheSummaryReadInEnglish() {
        val rows = rows(SYNTHETIC)

        assertEquals(listOf("Surface", "Way type"), rows.map { it.title })
        assertEquals("50% asphalt · 30% gravel · 20% ground", rows[0].summary)
        assertEquals("50% street · 50% track", rows[1].summary)
    }

    @Test
    fun theRowsAndTheSummaryReadInUkrainian() {
        val rows = elevationStripRows(SYNTHETIC, MapStrings(UKRAINIAN), UKRAINIAN_LOCALE)

        assertEquals(listOf("Поверхня", "Тип шляху"), rows.map { it.title })
        assertEquals("50% асфальт · 30% гравій · 20% грунт", rows[0].summary)
        assertEquals("50% вулиця · 50% ґрунтівка", rows[1].summary)
    }

    @Test
    fun thePaletteIsTheRound10OneInBothThemes() {
        assertEquals(Color(0xFF4A3AA7), elevationStripColour(ElevationStripBand.Asphalt, dark = false))
        assertEquals(Color(0xFFE87BA4), elevationStripColour(ElevationStripBand.Paving, dark = false))
        assertEquals(Color(0xFF008300), elevationStripColour(ElevationStripBand.Ground, dark = false))
        assertEquals(Color(0xFFEDA100), elevationStripColour(ElevationStripBand.Gravel, dark = false))
        assertEquals(Color(0xFF2A78D6), elevationStripColour(ElevationStripBand.Street, dark = false))
        assertEquals(Color(0xFFE87BA4), elevationStripColour(ElevationStripBand.Footway, dark = false))
        assertEquals(Color(0xFF008300), elevationStripColour(ElevationStripBand.Path, dark = false))
        assertEquals(Color(0xFFEDA100), elevationStripColour(ElevationStripBand.Track, dark = false))

        assertEquals(Color(0xFF9085E9), elevationStripColour(ElevationStripBand.Asphalt, dark = true))
        assertEquals(Color(0xFFD55181), elevationStripColour(ElevationStripBand.Paving, dark = true))
        assertEquals(Color(0xFF008300), elevationStripColour(ElevationStripBand.Ground, dark = true))
        assertEquals(Color(0xFFC98500), elevationStripColour(ElevationStripBand.Gravel, dark = true))
        assertEquals(Color(0xFF3987E5), elevationStripColour(ElevationStripBand.Street, dark = true))
        assertEquals(Color(0xFFD55181), elevationStripColour(ElevationStripBand.Footway, dark = true))
        assertEquals(Color(0xFF008300), elevationStripColour(ElevationStripBand.Path, dark = true))
        assertEquals(Color(0xFFC98500), elevationStripColour(ElevationStripBand.Track, dark = true))
    }

    @Test
    fun aCityNameIsNotAClass() {
        assertNull(surfaceBand("cobbles_of_doubt"))
        assertNull(surfaceBand(null))
        assertNull(wayTypeBand("promenade"))
        assertEquals(ElevationStripBand.Paving, surfaceBand("Paving_Stones"))
        assertEquals(ElevationStripBand.Steps, wayTypeBand(" STEPS "))
    }

    private fun rows(state: ElevationChartState): List<ElevationStripRow> = elevationStripRows(state, MapStrings(ENGLISH), LOCALE)

    private fun fractionAt(
        state: ElevationChartState,
        distanceMeters: Double,
    ): Float = elevationChartXAtDistance(state, distanceMeters) ?: error("no fraction at $distanceMeters m")

    private fun row(
        rows: List<ElevationStripRow>,
        title: String,
    ): ElevationStripRow =
        rows.firstOrNull { it.title == title }
            ?: error("no $title row in ${rows.map { it.title }}")

    private fun chart(
        vararg stretches: ElevationChartStretch,
        points: List<ElevationChartPoint> = DISTANCE_POINTS,
        axis: ElevationChartAxis = ElevationChartAxis.Distance,
    ): ElevationChartState = ElevationChartState(points = points, axis = axis, stretches = stretches.toList())

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
        const val TOLERANCE = 1e-6f
        val LOCALE: Locale = Locale.ENGLISH
        val UKRAINIAN_LOCALE: Locale = Locale.forLanguageTag("uk")

        val DISTANCE_POINTS =
            listOf(
                ElevationChartPoint(0.0, 300.0),
                ElevationChartPoint(2_500.0, 340.0),
                ElevationChartPoint(5_000.0, 320.0),
            )

        val TIMED_POINTS =
            listOf(
                ElevationChartPoint(0.0, 300.0, elapsedMillis = 0L),
                ElevationChartPoint(2_500.0, 340.0, elapsedMillis = 500_000L),
                ElevationChartPoint(5_000.0, 320.0, elapsedMillis = 2_000_000L),
            )

        val TIMED_STATE =
            ElevationChartState(
                points = TIMED_POINTS,
                stretches =
                    listOf(
                        ElevationChartStretch(0.0, 2_500.0, surface = "asphalt"),
                        ElevationChartStretch(2_500.0, 5_000.0, surface = "gravel"),
                    ),
            )

        val SYNTHETIC =
            ElevationChartState(
                points = DISTANCE_POINTS,
                stretches =
                    listOf(
                        ElevationChartStretch(0.0, 2_500.0, surface = "asphalt", wayType = "residential"),
                        ElevationChartStretch(2_500.0, 4_000.0, surface = "gravel", wayType = "track"),
                        ElevationChartStretch(4_000.0, 5_000.0, surface = "ground", wayType = "track"),
                    ),
            )

        val ENGLISH =
            mapOf(
                "elevation_chart_surface_title" to "Surface",
                "elevation_chart_way_type_title" to "Way type",
                "elevation_chart_surface_asphalt" to "Asphalt",
                "elevation_chart_surface_paving" to "Paving",
                "elevation_chart_surface_ground" to "Ground",
                "elevation_chart_surface_gravel" to "Gravel",
                "elevation_chart_way_street" to "Street",
                "elevation_chart_way_footway" to "Footway",
                "elevation_chart_way_path" to "Path",
                "elevation_chart_way_track" to "Track",
                "elevation_chart_way_steps" to "Steps",
                "elevation_chart_strip_share" to "%1\$s%% %2\$s",
            )

        val UKRAINIAN =
            mapOf(
                "elevation_chart_surface_title" to "Поверхня",
                "elevation_chart_way_type_title" to "Тип шляху",
                "elevation_chart_surface_asphalt" to "Асфальт",
                "elevation_chart_surface_paving" to "Бруківка",
                "elevation_chart_surface_ground" to "Грунт",
                "elevation_chart_surface_gravel" to "Гравій",
                "elevation_chart_way_street" to "Вулиця",
                "elevation_chart_way_footway" to "Пішохідна",
                "elevation_chart_way_path" to "Стежка",
                "elevation_chart_way_track" to "Ґрунтівка",
                "elevation_chart_way_steps" to "Сходи",
                "elevation_chart_strip_share" to "%1\$s%% %2\$s",
            )
    }
}
