package nl.r1a.grassmap.ui.shell

import androidx.compose.ui.graphics.Color
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.ElevationChartAxis
import nl.r1a.grassmap.plugin.ui.api.ElevationChartPoint
import nl.r1a.grassmap.plugin.ui.api.ElevationChartRange
import nl.r1a.grassmap.plugin.ui.api.ElevationChartRangeStats
import nl.r1a.grassmap.plugin.ui.api.ElevationChartState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** The elevation chart's labels, axis chips and its mapping from the profile (T-0404). */
class ElevationChartContentTest {
    @Test
    fun theChartKeepsTheEngineSamplesAndOnlyMovesThemAlongTheAxis() {
        val content = chart(ElevationChartState(points = TIMED_POINTS))

        assertEquals(3, content.line.size)
        assertEquals(300.0, content.minElevationMeters, 0.0)
        assertEquals(340.0, content.maxElevationMeters, 0.0)
        assertEquals(0f, content.line.first().x, 1e-6f)
        assertEquals(1f, content.line.last().x, 1e-6f)
    }

    @Test
    fun eachAxisMapsAKnownSampleToItsExpectedX() {
        val distance = ElevationChartState(points = TIMED_POINTS, axis = ElevationChartAxis.Distance)
        val duration = ElevationChartState(points = TIMED_POINTS, axis = ElevationChartAxis.Duration)
        val timeOfDay = ElevationChartState(points = TIMED_POINTS, axis = ElevationChartAxis.TimeOfDay)

        assertEquals(0.0, valueAt(elevationChartX(distance, 0)), 1e-6)
        assertEquals(0.5, valueAt(elevationChartX(distance, 1)), 1e-6)
        assertEquals(1.0, valueAt(elevationChartX(distance, 2)), 1e-6)
        assertEquals(0.0, valueAt(elevationChartX(duration, 0)), 1e-6)
        assertEquals(0.5, valueAt(elevationChartX(duration, 1)), 1e-6)
        assertEquals(1.0, valueAt(elevationChartX(duration, 2)), 1e-6)
        assertEquals(0.0, valueAt(elevationChartX(timeOfDay, 0)), 1e-6)
        assertEquals(0.5, valueAt(elevationChartX(timeOfDay, 1)), 1e-6)
        assertEquals(1.0, valueAt(elevationChartX(timeOfDay, 2)), 1e-6)
    }

    @Test
    fun theAxisChipsNameTheThreeAxesAndSelectOne() {
        val content = chart(ElevationChartState(points = TIMED_POINTS))

        assertEquals(listOf("Distance", "Duration", "Time of day"), content.axes.map { it.label })
        assertEquals(listOf(true, false, false), content.axes.map { it.selected })
    }

    @Test
    fun theDurationChipIsSelectedWhenThatAxisIsChosen() {
        val content = chart(ElevationChartState(points = TIMED_POINTS, axis = ElevationChartAxis.Duration))

        assertEquals(listOf(false, true, false), content.axes.map { it.selected })
        assertEquals(ElevationChartAxis.Duration, content.axis)
    }

    @Test
    fun anUntimedProfileOffersOnlyTheDistanceAxis() {
        val content = chart(ElevationChartState(points = UNTIMED_POINTS))

        assertEquals(listOf("Distance"), content.axes.map { it.label })
        assertEquals(ElevationChartAxis.Distance, content.axis)
    }

    @Test
    fun aChosenTimeAxisFallsBackToDistanceWhenTheProfileHasNoTiming() {
        val state = ElevationChartState(points = UNTIMED_POINTS, axis = ElevationChartAxis.TimeOfDay)

        assertEquals(ElevationChartAxis.Distance, state.effectiveAxis)
        assertEquals(ElevationChartAxis.Distance, chart(state).axis)
    }

    @Test
    fun aShortOrEmptyProfileDrawsNothing() {
        assertNull(
            elevationChartContent(
                ElevationChartState(points = listOf(TIMED_POINTS.first())),
                MapStrings(ENGLISH),
                LOCALE,
                ZONE,
            ),
        )
        assertNull(elevationChartContent(ElevationChartState(), MapStrings(ENGLISH), LOCALE, ZONE))
    }

    @Test
    fun theDistanceAxisLabelsAWholeKilometreSpanInMetres() {
        val content = chart(ElevationChartState(points = TIMED_POINTS))

        assertEquals(
            listOf("0 m", "200 m", "400 m", "600 m", "800 m", "1000 m"),
            content.xTicks.map { it.label },
        )
        assertEquals(0f, content.xTicks.first().fraction, 1e-6f)
        assertEquals(1f, content.xTicks.last().fraction, 1e-6f)
    }

    @Test
    fun aLongDistanceAxisLabelsWholeKilometres() {
        val points =
            listOf(
                ElevationChartPoint(distanceMeters = 0.0, elevationMeters = 280.0),
                ElevationChartPoint(distanceMeters = 2_300.0, elevationMeters = 300.0),
                ElevationChartPoint(distanceMeters = 4_600.0, elevationMeters = 340.0),
            )

        val content = chart(ElevationChartState(points = points))

        assertEquals(
            listOf("0 km", "1 km", "2 km", "3 km", "4 km", "4.6 km"),
            content.xTicks.map { it.label },
        )
    }

    @Test
    fun theDurationAxisLabelsItsOwnTimes() {
        val content = chart(ElevationChartState(points = TIMED_POINTS, axis = ElevationChartAxis.Duration))

        assertEquals(listOf("0 min", "15 min", "30 min"), content.xTicks.map { it.label })
    }

    @Test
    fun theTimeOfDayAxisLabelsClockTimes() {
        val content = chart(ElevationChartState(points = TIMED_POINTS, axis = ElevationChartAxis.TimeOfDay))

        val labels = content.xTicks.map { it.label }
        assertTrue(labels.isNotEmpty())
        assertTrue(labels.all { label -> CLOCK.matches(label) })
    }

    @Test
    fun theElevationLabelsCarryMetres() {
        val content = chart(ElevationChartState(points = TIMED_POINTS))

        assertEquals(listOf("340 m", "327 m", "313 m", "300 m"), content.yTicks.map { it.label })
    }

    @Test
    fun aSteadyClimbIsFilledWithTheClimbColour() {
        val content = chart(ElevationChartState(points = TIMED_POINTS))

        assertEquals(2, content.slopes.size)
        assertTrue(content.slopes.all { slope -> slope.bin > FLAT_BIN })
    }

    @Test
    fun theSlopeScaleIsSevenBinsFromSteepDownToSteepUp() {
        assertEquals(0, elevationSlopeBin(-20.0))
        assertEquals(1, elevationSlopeBin(-10.0))
        assertEquals(2, elevationSlopeBin(-4.0))
        assertEquals(3, elevationSlopeBin(0.0))
        assertEquals(4, elevationSlopeBin(5.0))
        assertEquals(5, elevationSlopeBin(10.0))
        assertEquals(6, elevationSlopeBin(20.0))
        assertEquals(7, ELEVATION_SLOPE_BINS)
    }

    @Test
    fun theLightAndDarkPalettesUseTheValidatedRound10Colours() {
        val light = elevationChartPalette(dark = false)
        val dark = elevationChartPalette(dark = true)

        assertEquals("#0D366B", light.slope.first().toHex())
        assertEquals("#8F1D1A", light.slope.last().toHex())
        assertEquals("#B7D3F6", dark.slope.first().toHex())
        assertEquals("#FFC4BC", dark.slope.last().toHex())
        assertNotEquals(light.surface, dark.surface)
        assertNotEquals(light.line, dark.line)
        assertEquals(ELEVATION_SLOPE_BINS, light.slope.size)
        assertEquals(ELEVATION_SLOPE_BINS, dark.slope.size)
    }

    @Test
    fun theLabelsResolveInUkrainian() {
        val content = chart(ElevationChartState(points = TIMED_POINTS), MapStrings(UKRAINIAN))

        assertEquals("Висота", content.title)
        assertEquals(listOf("Відстань", "Тривалість", "Час доби"), content.axes.map { it.label })
        assertFalse(content.xTicks.isEmpty())
    }

    @Test
    fun aDragFractionMapsBackToTheDistanceItCovers() {
        val distance = ElevationChartState(points = TIMED_POINTS)
        val duration = ElevationChartState(points = TIMED_POINTS, axis = ElevationChartAxis.Duration)
        val timeOfDay = ElevationChartState(points = TIMED_POINTS, axis = ElevationChartAxis.TimeOfDay)

        assertEquals(0.0, elevationDistanceAt(distance, 0f) ?: -1.0, 1e-6)
        assertEquals(500.0, elevationDistanceAt(distance, 0.5f) ?: -1.0, 1e-6)
        assertEquals(1_000.0, elevationDistanceAt(distance, 1f) ?: -1.0, 1e-6)
        assertEquals(500.0, elevationDistanceAt(duration, 0.5f) ?: -1.0, 1e-6)
        assertEquals(500.0, elevationDistanceAt(timeOfDay, 0.5f) ?: -1.0, 1e-6)
        assertEquals(250.0, elevationDistanceAt(distance, 0.25f) ?: -1.0, 1e-6)
    }

    @Test
    fun aDistanceMapsOntoItsPlaceOnEveryAxis() {
        val distance = ElevationChartState(points = TIMED_POINTS)
        val duration = ElevationChartState(points = TIMED_POINTS, axis = ElevationChartAxis.Duration)

        assertEquals(0.5, valueAt(elevationChartXAtDistance(distance, 500.0)), 1e-6)
        assertEquals(0.25, valueAt(elevationChartXAtDistance(distance, 250.0)), 1e-6)
        assertEquals(0.5, valueAt(elevationChartXAtDistance(duration, 500.0)), 1e-6)
        assertEquals(0.0, valueAt(elevationChartXAtDistance(distance, -100.0)), 1e-6)
        assertEquals(1.0, valueAt(elevationChartXAtDistance(distance, 9_000.0)), 1e-6)
    }

    @Test
    fun theSelectedRangeDrawsTheBandWhereItLies() {
        val state = ElevationChartState(points = TIMED_POINTS, selection = SELECTION)

        val band = chart(state).band

        assertEquals(0.25f, band?.startFraction ?: -1f, 1e-6f)
        assertEquals(0.75f, band?.endFraction ?: -1f, 1e-6f)
    }

    @Test
    fun theBandFollowsTheChosenAxis() {
        val state =
            ElevationChartState(
                points = TIMED_POINTS,
                axis = ElevationChartAxis.Duration,
                selection = SELECTION,
            )

        val band = chart(state).band

        assertEquals(0.25f, band?.startFraction ?: -1f, 1e-6f)
        assertEquals(0.75f, band?.endFraction ?: -1f, 1e-6f)
    }

    @Test
    fun anUnselectedChartHasNoBand() {
        assertNull(chart(ElevationChartState(points = TIMED_POINTS)).band)
    }

    @Test
    fun aClimbBadgeChoosesItsNumberByContrast() {
        assertEquals(Color(0xFFFFFFFF), elevationBadgeInk(Color(0xFFD9473E)))
        assertEquals(Color(0xFF202124), elevationBadgeInk(Color(0xFFEE8C80)))
        assertEquals(Color(0xFFFFFFFF), elevationBadgeInk(Color(0xFF8F1D1A)))
        assertEquals(Color(0xFF202124), elevationBadgeInk(Color(0xFFFFC4BC)))
    }

    private fun chart(
        state: ElevationChartState,
        strings: Strings = MapStrings(ENGLISH),
    ): ElevationChartContent {
        val content = elevationChartContent(state, strings, LOCALE, ZONE)
        assertNotNull(content)
        return content ?: error("no chart content")
    }

    private fun valueAt(fraction: Float?): Double {
        assertNotNull(fraction)
        return (fraction ?: error("no fraction")).toDouble()
    }

    private fun Color.toHex(): String = "#%06X".format(value.toLong().shr(32).and(0xFFFFFF))

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
        const val FLAT_BIN = 3
        const val START_MILLIS = 1_700_000_000_000L
        val LOCALE: Locale = Locale.ENGLISH
        val ZONE: ZoneId = ZoneId.of("UTC")
        val CLOCK = Regex("\\d{2}:\\d{2}")

        val TIMED_POINTS =
            listOf(
                ElevationChartPoint(
                    distanceMeters = 0.0,
                    elevationMeters = 300.0,
                    elapsedMillis = 0L,
                    timeOfDayMillis = START_MILLIS,
                ),
                ElevationChartPoint(
                    distanceMeters = 500.0,
                    elevationMeters = 320.0,
                    elapsedMillis = 900_000L,
                    timeOfDayMillis = START_MILLIS + 900_000L,
                ),
                ElevationChartPoint(
                    distanceMeters = 1_000.0,
                    elevationMeters = 340.0,
                    elapsedMillis = 1_800_000L,
                    timeOfDayMillis = START_MILLIS + 1_800_000L,
                ),
            )

        val UNTIMED_POINTS =
            listOf(
                ElevationChartPoint(distanceMeters = 0.0, elevationMeters = 300.0),
                ElevationChartPoint(distanceMeters = 1_000.0, elevationMeters = 320.0),
            )

        val SELECTION =
            ElevationChartRangeStats(
                range = ElevationChartRange(startMeters = 250.0, endMeters = 750.0),
                distanceMeters = 500.0,
                ascentMeters = 20.0,
                descentMeters = 0.0,
                averageGradePercent = 4.0,
                durationMillis = 900_000L,
            )

        val ENGLISH =
            mapOf(
                "elevation_chart_title" to "Elevation",
                "elevation_chart_axis_distance" to "Distance",
                "elevation_chart_axis_duration" to "Duration",
                "elevation_chart_axis_time_of_day" to "Time of day",
                "elevation_chart_metres" to "%1\$s m",
                "elevation_chart_kilometres" to "%1\$s km",
                "elevation_chart_minutes" to "%1\$s min",
                "elevation_chart_hours" to "%1\$s h",
                "elevation_chart_hours_minutes" to "%1\$s h %2\$s min",
            )

        val UKRAINIAN =
            mapOf(
                "elevation_chart_title" to "Висота",
                "elevation_chart_axis_distance" to "Відстань",
                "elevation_chart_axis_duration" to "Тривалість",
                "elevation_chart_axis_time_of_day" to "Час доби",
                "elevation_chart_metres" to "%1\$s м",
                "elevation_chart_kilometres" to "%1\$s км",
                "elevation_chart_minutes" to "%1\$s хв",
                "elevation_chart_hours" to "%1\$s год",
                "elevation_chart_hours_minutes" to "%1\$s год %2\$s хв",
            )
    }
}
