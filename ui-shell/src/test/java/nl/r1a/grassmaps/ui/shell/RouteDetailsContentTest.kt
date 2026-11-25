package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.ui.api.ElevationChartPoint
import nl.r1a.grassmaps.plugin.ui.api.ElevationChartRange
import nl.r1a.grassmaps.plugin.ui.api.ElevationChartRangeStats
import nl.r1a.grassmaps.plugin.ui.api.ElevationChartState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** The route-details header: the engine's gain, loss and steepest grade, with no invented values. */
class RouteDetailsContentTest {
    @Test
    fun theHeaderNamesTheEnginesGainLossAndSteepestGrade() {
        val summary = routeElevationSummary(ROUTE_STATE, strings(ENGLISH))

        assertEquals("↑62 m", summary?.up)
        assertEquals("↓15 m", summary?.down)
        assertEquals("steepest 7%", summary?.steepest)
        assertEquals("↑62 m  ↓15 m · steepest 7%", summary?.text)
    }

    @Test
    fun aFlatProfileOmitsEveryFieldTheEngineDidNotMeasure() {
        val state =
            ElevationChartState(
                points =
                    listOf(
                        ElevationChartPoint(distanceMeters = 0.0, elevationMeters = 300.0),
                        ElevationChartPoint(distanceMeters = 500.0, elevationMeters = 300.0),
                    ),
            )

        val summary = routeElevationSummary(state, strings(ENGLISH))

        assertNull(summary?.up)
        assertNull(summary?.down)
        assertNull(summary?.steepest)
        assertNull(summary?.text)
    }

    @Test
    fun theHeaderUsesUkrainianCopy() {
        val summary = routeElevationSummary(ROUTE_STATE, strings(UKRAINIAN))

        assertEquals("↑62 м", summary?.up)
        assertEquals("↓15 м", summary?.down)
        assertEquals("максимальний нахил 7%", summary?.steepest)
    }

    @Test
    fun aSelectedRangeUpdatesTheHeaderFromTheChartsSamples() {
        val state =
            ROUTE_STATE.copy(
                selection =
                    ElevationChartRangeStats(
                        range = ElevationChartRange(startMeters = 0.0, endMeters = 900.0),
                        distanceMeters = 900.0,
                        ascentMeters = 1.0,
                        descentMeters = 2.0,
                        averageGradePercent = 4.0,
                    ),
            )

        val summary = routeElevationSummary(state, strings(ENGLISH))

        assertEquals("↑62 m", summary?.up)
        assertNull(summary?.down)
        assertEquals("steepest 7%", summary?.steepest)
        assertEquals("↑62 m · steepest 7%", summary?.text)
    }

    @Test
    fun theSteepestValueIsTheStepGradeNotTheNetAverageGrade() {
        // A 10 % climb, then two 5 % descents: net grade over the range is 0 %,
        // but the steepest single step the chart draws is 10 %.
        val state =
            ElevationChartState(
                points =
                    listOf(
                        ElevationChartPoint(distanceMeters = 0.0, elevationMeters = 300.0),
                        ElevationChartPoint(distanceMeters = 100.0, elevationMeters = 310.0),
                        ElevationChartPoint(distanceMeters = 200.0, elevationMeters = 305.0),
                        ElevationChartPoint(distanceMeters = 300.0, elevationMeters = 300.0),
                    ),
                selection =
                    ElevationChartRangeStats(
                        range = ElevationChartRange(startMeters = 0.0, endMeters = 300.0),
                        distanceMeters = 300.0,
                        ascentMeters = 10.0,
                        descentMeters = 10.0,
                        averageGradePercent = 0.0,
                    ),
            )

        val summary = routeElevationSummary(state, strings(ENGLISH))

        assertEquals("steepest 10%", summary?.steepest)
        assertEquals("↑10 m  ↓10 m · steepest 10%", summary?.text)
    }

    @Test
    fun aDragRangeReadsItsGainAndGradeFromTheChartsEngineSamples() {
        val stats = elevationRangeStats(ROUTE_STATE, ElevationChartRange(0.0, 900.0))

        assertEquals(0.0, stats?.range?.startMeters ?: -1.0, 1e-9)
        assertEquals(900.0, stats?.range?.endMeters ?: -1.0, 1e-9)
        assertEquals(62.0, stats?.ascentMeters ?: -1.0, 1e-6)
        assertEquals(0.0, stats?.descentMeters ?: -1.0, 1e-6)
        assertEquals(62.0 / 900.0 * 100.0, stats?.averageGradePercent ?: -1.0, 1e-6)
    }

    private fun strings(values: Map<String, String>): Strings =
        object : Strings {
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
        /** A 62 m gain over 900 m (6.9 %, the design's 7 %) then a 15 m loss over 300 m. */
        val ROUTE_STATE =
            ElevationChartState(
                points =
                    listOf(
                        ElevationChartPoint(distanceMeters = 0.0, elevationMeters = 280.0),
                        ElevationChartPoint(distanceMeters = 900.0, elevationMeters = 342.0),
                        ElevationChartPoint(distanceMeters = 1_200.0, elevationMeters = 327.0),
                    ),
            )

        val ENGLISH =
            mapOf(
                "elevation_chart_range_ascent" to "\u2191%1\$s m",
                "elevation_chart_range_descent" to "\u2193%1\$s m",
                "route_details_steepest" to "steepest %1\$s%%",
            )

        val UKRAINIAN =
            mapOf(
                "elevation_chart_range_ascent" to "\u2191%1\$s м",
                "elevation_chart_range_descent" to "\u2193%1\$s м",
                "route_details_steepest" to "максимальний нахил %1\$s%%",
            )
    }
}
