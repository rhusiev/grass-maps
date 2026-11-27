package nl.r1a.grassmaps.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Final blind review P1-1: the Layers button was drawn in a bottom-end column
 * that the permanently composed bottom bar and its chevron painted over. Layers
 * now takes a bottom inset derived from the bar's reach, keeps its own host, and
 * composes before the bar. Mappers are Composables a JVM test cannot render, so
 * pin the composition shape from source.
 */
class MapLayersPlacementTest {
    private val source = File(HOST_FILE).readText()

    @Test
    fun layersHasItsOwnHostWithABottomInsetAboveTheBar() {
        val layers =
            source
                .substringAfter("if (!navigationActive && !bottomSheetShown) {")
                .substringBefore("if (mapControlsShown(navigationActive, plan.status)) {\n            val dispatchMapButton")

        assertTrue("LayersButton is missing from its own host", layers.contains("LayersButton("))
        assertTrue("Layers lost its BottomEnd anchor", layers.contains("Alignment.BottomEnd"))
        assertTrue("Layers has no bottom inset above the bar", layers.contains("bottomInsets.layersDp.dp"))
        assertTrue(
            "Layers is composed after the bar, so the bar paints over it",
            source.indexOf("LayersButton(") < source.indexOf("BottomBarChevron("),
        )
    }

    @Test
    fun theBottomInsetsComeFromTheBarAndKeepTheArcEndClear() {
        val host = source.substringAfter("internal fun mapBottomInsets").substringBefore("\n}\n")

        assertTrue("the inset ignores the chevron", host.contains("SHEET_CHEVRON_TARGET_DP"))
        assertTrue("the inset ignores the bar's credit spacer", host.contains("PROFILE_SWITCH_CREDIT_GAP_DP"))
        assertTrue("the inset ignores the bar's drop", host.contains("PROFILE_SWITCH_DROP_DP"))
        assertTrue("the arc mode does not raise the scale bar above the bar", host.contains("barTop + LAYERS_GAP_DP"))
        assertTrue(
            "Layers does not use the derived inset",
            source.contains(".padding(bottom = bottomInsets.layersDp.dp)"),
        )
    }

    @Test
    fun theInsetClearsTheBarAndTheScaleBarStacksAboveLayers() {
        val default = mapBottomInsets(arc = false)
        val barReach = SHEET_CHEVRON_TARGET_DP + PROFILE_SWITCH_CREDIT_GAP_DP - PROFILE_SWITCH_DROP_DP

        assertTrue(
            "Layers at ${default.layersDp} dp must clear the $barReach dp bar reach",
            default.layersDp > barReach,
        )
        assertTrue(
            "Layers at ${default.layersDp} dp must clear the $SHEET_CHEVRON_TARGET_DP dp chevron",
            default.layersDp > SHEET_CHEVRON_TARGET_DP,
        )
        assertTrue(
            "the scale bar at ${default.scaleBarDp} dp must sit above Layers",
            default.scaleBarDp >= default.layersDp + LAYERS_HEIGHT_DP + SCALE_GAP_DP,
        )
        val arc = mapBottomInsets(arc = true)
        val arcBarTop = BAR_PADDING_DP - PROFILE_SWITCH_DROP_DP + MAP_BOTTOM_BAR_HEIGHT_DP
        assertTrue(
            "the arc scale bar at ${arc.scaleBarDp} dp still sits under the bar at $arcBarTop dp",
            arc.scaleBarDp >= arcBarTop + ARC_SCALE_BAR_GAP_DP,
        )
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/app/MapScreen.kt"

        // The measured Layers button height and the existing scale-bar gap.
        const val LAYERS_HEIGHT_DP = 48
        const val SCALE_GAP_DP = 24
        const val ARC_SCALE_BAR_GAP_DP = 8
    }
}
