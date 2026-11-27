package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The JVM suite cannot render the planner (T-0612). These tests read the source
 * of the route-details call so the canon composition is checked without a device:
 * a range callback, the SHOW ALONG grouping, the elevation header and no colour
 * selector in this call. Colour selection stays in the generic chart for track
 * analysis.
 */
class RouteDetailsCompositionTest {
    @Test
    fun theRouteDetailsChartIsARangeSelector() {
        assertTrue(
            "the route-details chart reports a selected range",
            "onRange =" in detailsCall(),
        )
    }

    @Test
    fun theRouteDetailsChartUsesTheCanonGrouping() {
        val call = detailsCall()
        assertTrue("the axis chips sit under SHOW ALONG", "showAlong = true" in call)
        assertTrue("the elevation header is drawn beside the title", "summary =" in call)
    }

    @Test
    fun theRouteDetailsChartDoesNotExposeColourSelection() {
        assertTrue("colour selection is off in the route-details call", "showColour = false" in detailsCall())
    }

    @Test
    fun elevationAxisLabelsGrowWithoutWrappingAtFontTwo() {
        val chart = File("src/main/java/nl/r1a/grassmaps/ui/shell/ElevationChart.kt").readText()
        val yLabels = chart.substringAfter("content.yTicks.forEach { tick ->").substringBefore("\n                }")

        assertTrue("the y-axis column is fixed", chart.contains(".widthIn(min = Y_LABEL_WIDTH_DP.dp)"))
        assertTrue("the y-axis label still wraps at font 2.0", yLabels.contains("softWrap = false"))
        assertTrue("an exact y-axis width came back", !chart.contains(".width(Y_LABEL_WIDTH_DP.dp)"))
    }

    @Test
    fun colourSelectionStaysInTheGenericChartForTrackAnalysis() {
        val chart = File("src/main/java/nl/r1a/grassmaps/ui/shell/ElevationChart.kt").readText()
        assertTrue("the generic chart still draws its colour chips", "content.colours.forEach" in chart)
        assertTrue("the colour chips are gated by a parameter", "if (showColour)" in chart)
        val track = File("src/main/java/nl/r1a/grassmaps/ui/shell/TrackAnalysisContent.kt").readText()
        assertTrue("track analysis keeps colour selection", "showColour = false" !in track)
    }

    /** The chart call inside the route panel's expanded-details branch. */
    private fun detailsCall(): String =
        File("src/main/java/nl/r1a/grassmaps/ui/shell/RoutePlanPanel.kt")
            .readText()
            .substringAfter("private fun RouteChartSection(")
            .substringAfter("ElevationChart(")
            .substringBefore("\n    )")
}
