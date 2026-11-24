package nl.r1a.grassmaps.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612 final-review defect: at launch the collapsed "Search places" pill sat
 * over the map credit, and the scale bar sat inside the Settings button area.
 * The credit inset follows the tallest reported bottom control; the right rail
 * and the pill must therefore share one bottom lane, and the credit needs a
 * named gap above that lane. Source-checked: Mappers and MapSurfaceView are
 * Composables a JVM test cannot measure.
 */
class MapBottomChromePlacementTest {
    private val source = File(HOST_FILE).readText()

    @Test
    fun theCreditAddsANamedGapAboveTheTallestBottomControl() {
        assertTrue(
            "the credit has no clearance above the bottom chrome",
            constant("MAP_CREDIT_CLEARANCE_DP") >= 8,
        )
        assertTrue(
            "the credit reads the raw inset without a gap",
            source.contains("(LocalBottomChrome.current?.insetPx ?: 0).toDp() + MAP_CREDIT_CLEARANCE_DP.dp"),
        )
    }

    @Test
    fun theCollapsedPillReportsItsTopEdgeToTheChrome() {
        val collapsed = source.substringAfter("if (expanded) {").substringBefore("collapsedAnchor =")
        assertTrue(
            "the search pill is invisible to the map credit",
            collapsed.contains("reportsBottomChrome(key = SEARCH_PILL_CHROME_KEY)"),
        )
        assertTrue(
            "the pill lost its named bottom lane",
            collapsed.contains("SEARCH_PILL_BOTTOM_DP.dp"),
        )
    }

    @Test
    fun theScaleBarSitsAboveLayersWithAClearGap() {
        assertTrue(
            "the scale bar has no named gap above the Settings button",
            constant("SCALE_BAR_GAP_DP") >= 16,
        )
        val rail = source.substringAfter("val dismissLayers").substringBefore("MapControlBar(")
        assertTrue(
            "the scale bar no longer sits above Layers",
            rail.indexOf("MapScaleOverlay(") < rail.indexOf("LayersButton("),
        )
        assertTrue(
            "the rail does not use the named gap",
            rail.contains("spacedBy(SCALE_BAR_GAP_DP.dp, Alignment.Bottom)"),
        )
        assertTrue(
            "the rail left the screen-edge lane and now hits the arc",
            rail.contains(".padding(BAR_PADDING_DP.dp)"),
        )
    }

    private fun constant(name: String): Int {
        val expression =
            Regex("""(?:private |internal )?const val $name = ([^\n]+)""")
                .find(source)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?: throw AssertionError("$name is not a named constant in MapScreen.kt")
        return expression.split("+").sumOf { term ->
            val token = term.trim()
            token.toIntOrNull() ?: constant(token)
        }
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/app/MapScreen.kt"
    }
}
