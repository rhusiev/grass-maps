package nl.r1a.grassmaps.app

import androidx.compose.ui.unit.dp
import nl.r1a.grassmaps.core.services.MapButtonLayout
import nl.r1a.grassmaps.core.services.MapControlCommands
import nl.r1a.grassmaps.ui.shell.MapControlMetrics
import nl.r1a.grassmaps.ui.shell.thumbArcOffsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612, final design-aware review P1 6: the default rail sat at the top end,
 * outside the three approved exceptions to the bottom-reach rule. The default
 * now uses the one-thumb arc. This test models the drawn elements of that arc
 * and pins their clearance from every piece of bottom chrome. The earlier
 * top-end reason is obsolete because the arc's faces fan up and left from the
 * slider instead of forming a wide column that can reach Layers.
 */
class MapRailDefaultTest {
    @Test
    fun theDefaultRailIsBottomAnchored() {
        assertTrue("the default rail left the bottom thumb reach", MapButtonLayout.DEFAULT.arc)
    }

    @Test
    fun theBottomRailClearsEveryBottomChromeElement() {
        assertTrue("the clearance model only describes the arc rail", MapButtonLayout.DEFAULT.arc)
        val rail = railRects()
        val chrome =
            listOf(
                "the single bar" to bottomBar(),
                "the sheet chevron" to chevron(),
                "Layers" to layers(),
                "the map credit" to credit(),
                "the scale bar" to scaleBar(),
            )
        chrome.forEach { (name, rect) ->
            rail.forEach { (part, face) ->
                assertNoOverlap(part, name, face, rect)
            }
        }
    }

    /**
     * The first clearance model only checked the rail against the chrome. Train
     * 114's bottom-centred Layers and bottom-end scale bar then covered the
     * credit and the chevron. This pins the four visible elements of that arc
     * against each other, plus the bar that holds the chevron.
     */
    @Test
    fun theArcBottomChromeElementsDoNotOverlapEachOther() {
        assertTrue("the clearance model only describes the arc rail", MapButtonLayout.DEFAULT.arc)
        val chrome =
            listOf(
                "the map credit" to credit(),
                "Layers" to layers(),
                "the scale bar" to scaleBar(),
                "the sheet chevron" to chevron(),
            )
        chrome.indices.forEach { first ->
            (first + 1 until chrome.size).forEach { second ->
                val (firstName, firstRect) = chrome[first]
                val (secondName, secondRect) = chrome[second]
                assertNoOverlap(firstName, secondName, firstRect, secondRect)
            }
        }
        listOf("the map credit" to credit(), "Layers" to layers(), "the scale bar" to scaleBar()).forEach { (name, rect) ->
            assertNoOverlap(name, "the bottom bar", rect, bottomBar())
        }
    }

    @Test
    fun theCreditIsLiftedAboveTheCentredLayersButton() {
        val marks = bottomMarkInsets(arc = true, navigationActive = false, bottomSheetShown = false)
        assertEquals(ARC_CREDIT_LIFT_DP.dp, marks.credit)
        assertEquals(
            "the lift no longer clears Layers, the logo and its overlay padding",
            LAYERS_HEIGHT_DP + LAYERS_GAP_DP + CREDIT_LOGO_GAP_DP,
            ARC_CREDIT_LIFT_DP,
        )
    }

    /**
     * Final blind review 7 P1: the credit lift was gated on the arc and sheet
     * state while the logo inset was not. A suppressed credit then sat on the
     * logo, by 95 px in the route-failed and navigation screenshots. Both marks
     * must read one decision; a private gate on either one breaks the stack.
     */
    @Test
    fun theCreditAndTheLogoReadOneSharedLiftDecision() {
        val source = File(HOST_FILE).readText()
        val wiring = source.substringAfter("creditBottomInset =").substringBefore("distanceByTap")
        assertTrue(
            "the credit still computes its own conditional lift",
            wiring.contains("bottomMarks.credit"),
        )
        assertFalse(
            "a state gate on the credit separates it from the logo",
            wiring.contains("navigationActive") || wiring.contains("bottomSheetShown"),
        )
        val logoWiring = source.substringAfter("logoBottomInset =").substringBefore("distanceByTap")
        assertTrue(
            "the logo still ignores the shared lift decision",
            logoWiring.contains("bottomMarks.logo"),
        )
        assertFalse(
            "a state gate on the logo separates it from the credit",
            logoWiring.contains("navigationActive") || logoWiring.contains("bottomSheetShown"),
        )
    }

    /**
     * The shared decision raises exactly one mark, so neither state branch can
     * put the credit on the logo. This models the four states the blind review
     * captured: idle, bottom sheet, navigation and failed route planning.
     */
    @Test
    fun theSharedLiftKeepsTheMarksApartInEveryState() {
        val states =
            listOf(
                Triple(true, false, false), // idle arc
                Triple(true, false, true), // bottom sheet, including a failed plan
                Triple(true, true, false), // navigation
                Triple(false, false, false), // a profile with the arc off
            )
        states.forEach { (arc, navigationActive, bottomSheetShown) ->
            val marks = bottomMarkInsets(arc, navigationActive, bottomSheetShown)
            val base = bottomBar().top
            val credit =
                Rect(
                    left = ATTRIBUTION_MARGIN_DP,
                    right = ATTRIBUTION_MARGIN_DP + CREDIT_WIDTH_DP,
                    bottom = base + MAP_CREDIT_CLEARANCE_DP + ATTRIBUTION_CLEARANCE_DP + marks.credit.value.toInt(),
                    top = base + MAP_CREDIT_CLEARANCE_DP + ATTRIBUTION_CLEARANCE_DP + marks.credit.value.toInt() + CREDIT_HEIGHT_DP,
                )
            val logo =
                Rect(
                    left = ATTRIBUTION_MARGIN_DP,
                    right = ATTRIBUTION_MARGIN_DP + LOGO_WIDTH_DP,
                    bottom = base + sourceConstant("LOGO_CLEARANCE_DP") + marks.logo.value.toInt(),
                    top = base + sourceConstant("LOGO_CLEARANCE_DP") + marks.logo.value.toInt() + LOGO_HEIGHT_DP,
                )
            assertNoOverlap("the credit in arc=$arc nav=$navigationActive sheet=$bottomSheetShown", "the MapLibre logo", credit, logo)
            assertTrue("the credit fell behind the bottom chrome", credit.bottom >= base)
            assertTrue("the logo fell behind the bottom chrome", logo.bottom >= base)
        }
    }

    /**
     * Final blind review round 6 P2-4: the engine's pale logo sat at the window
     * bottom, over the bottom bar's Search control. It must follow the same
     * bottom-chrome reach the credit line reads, with its own clearance, so it
     * rises above the bar instead of hiding behind it.
     */
    @Test
    fun theLogoInsetFollowsTheBottomChrome() {
        val source = File(HOST_FILE).readText()
        val wiring = source.substringAfter("logoBottomInset =").substringBefore("distanceByTap")
        assertTrue(
            "the logo still sits at the window bottom under the bar",
            wiring.contains("LocalBottomChrome.current?.insetPx") && wiring.contains("LOGO_CLEARANCE_DP"),
        )
    }

    @Test
    fun theMapLibreLogoClearsTheBarAndTheCreditLine() {
        val logo = logo()
        assertNoOverlap("the MapLibre logo", "the bottom bar", logo, bottomBar())
        assertNoOverlap("the MapLibre logo", "the map credit", logo, credit())
        assertNoOverlap("the MapLibre logo", "Layers", logo, layers())
        assertNoOverlap("the MapLibre logo", "the scale bar", logo, scaleBar())
        assertNoOverlap("the MapLibre logo", "the sheet chevron", logo, chevron())
    }

    @Test
    fun theBottomRailStaysWithinOneThumbReach() {
        assertTrue("the clearance model only describes the arc rail", MapButtonLayout.DEFAULT.arc)
        val slider = railRects().first().second
        // One bar height above the bar's top edge, not twice the bar's height
        // measured from the screen bottom: the 16 dp bottom inset is separate.
        assertTrue(
            "the slider sits ${slider.bottom} dp above the screen bottom, out of thumb reach",
            slider.bottom <= BAR_PADDING_DP + MAP_BOTTOM_BAR_HEIGHT_DP * 2,
        )
        assertTrue("the arc faces left the screen", railRects().minOf { it.second.left } >= 0)
    }

    /**
     * The slider replaces the placed zoom pair, so only the other placed
     * commands are drawn as arc faces. The order is the layout's slot order.
     */
    private fun drawnFaceCount(): Int {
        val faces =
            MapButtonLayout.DEFAULT.commands.count { id ->
                id != MapControlCommands.ZOOM_IN && id != MapControlCommands.ZOOM_OUT
            }
        assertEquals("the default would draw more faces than the arc model allows", 3, faces)
        return faces
    }

    /** The slider first, then one box per drawn face, all relative to the screen bottom. */
    private fun railRects(): List<Pair<String, Rect>> {
        val sliderRight = SCREEN_WIDTH_DP - BAR_PADDING_DP
        val slider =
            Rect(
                left = sliderRight - MapControlMetrics.SLIDER_WIDTH_DP,
                right = sliderRight,
                bottom = ARC_BOTTOM_CLEARANCE_DP,
                top = ARC_BOTTOM_CLEARANCE_DP + MapControlMetrics.SLIDER_HEIGHT_DP,
            )
        // ArcRow is stacked above the slider with MapControlBar's 4 dp gap. Its
        // bottom-right corner is the anchor the thumb offsets are measured from.
        val arcBoxBottom = slider.top + ARC_STACK_GAP_DP
        val faces =
            thumbArcOffsets(drawnFaceCount()).mapIndexed { index, offset ->
                val right = sliderRight + offset.x.value.toInt()
                val bottom = arcBoxBottom - offset.y.value.toInt()
                "arc face $index" to
                    Rect(
                        left = right - MapControlMetrics.TAP_TARGET_DP,
                        right = right,
                        bottom = bottom,
                        top = bottom + MapControlMetrics.TAP_TARGET_DP,
                    )
            }
        return listOf("zoom slider" to slider) + faces
    }

    private fun bottomBar(): Rect =
        Rect(
            left = BAR_PADDING_DP,
            right = SCREEN_WIDTH_DP - BAR_PADDING_DP,
            bottom = BAR_PADDING_DP,
            top = BAR_PADDING_DP + MAP_BOTTOM_BAR_HEIGHT_DP,
        )

    private fun chevron(): Rect =
        Rect(
            left = SCREEN_WIDTH_DP - BAR_PADDING_DP - SHEET_CHEVRON_TARGET_DP,
            right = SCREEN_WIDTH_DP - BAR_PADDING_DP,
            bottom = BAR_PADDING_DP,
            top = BAR_PADDING_DP + MAP_BOTTOM_BAR_HEIGHT_DP,
        )

    private fun layers(): Rect {
        val insets = mapBottomInsets(arc = true)
        val centre = SCREEN_WIDTH_DP / 2
        return Rect(
            left = centre - LAYERS_HALF_WIDTH_DP,
            right = centre + LAYERS_HALF_WIDTH_DP,
            bottom = insets.layersDp,
            top = insets.layersDp + LAYERS_HEIGHT_DP,
        )
    }

    private fun credit(): Rect {
        val lift = bottomMarkInsets(arc = true, navigationActive = false, bottomSheetShown = false).credit.value.toInt()
        val bottom =
            bottomBar().top + MAP_CREDIT_CLEARANCE_DP + ATTRIBUTION_CLEARANCE_DP + lift
        return Rect(
            left = ATTRIBUTION_MARGIN_DP,
            right = ATTRIBUTION_MARGIN_DP + CREDIT_WIDTH_DP,
            bottom = bottom,
            top = bottom + CREDIT_HEIGHT_DP,
        )
    }

    private fun scaleBar(): Rect {
        val insets = mapBottomInsets(arc = true)
        return Rect(
            left = SCREEN_WIDTH_DP - BAR_PADDING_DP - SCALE_BAR_WIDTH_DP,
            right = SCREEN_WIDTH_DP - BAR_PADDING_DP,
            bottom = insets.scaleBarDp,
            top = insets.scaleBarDp + SCALE_BAR_HEIGHT_DP,
        )
    }

    /** The logo rises past the bar by the clearance the host declares. */
    private fun logo(): Rect {
        val bottom = bottomBar().top + sourceConstant("LOGO_CLEARANCE_DP")
        return Rect(
            left = ATTRIBUTION_MARGIN_DP,
            right = ATTRIBUTION_MARGIN_DP + LOGO_WIDTH_DP,
            bottom = bottom,
            top = bottom + LOGO_HEIGHT_DP,
        )
    }

    private fun sourceConstant(name: String): Int {
        val declaration =
            File(HOST_FILE)
                .readText()
                .lines()
                .firstOrNull { Regex("""(?:private |internal )?const val $name =""").containsMatchIn(it) }
                ?: throw AssertionError("MapScreen does not declare $name")
        return declaration.substringAfter("=").trim().toIntOrNull()
            ?: throw AssertionError("$name is not an integer dp constant")
    }

    private fun assertNoOverlap(
        part: String,
        chrome: String,
        rail: Rect,
        other: Rect,
    ) {
        val horizontal = rail.left < other.right && other.left < rail.right
        val vertical = rail.bottom < other.top && other.bottom < rail.top
        assertFalse(
            "$part overlaps $chrome: rail $rail, chrome $other",
            horizontal && vertical,
        )
    }

    /** An inclusive-exclusive box: dp from the screen start and from its bottom. */
    private data class Rect(
        val left: Int,
        val right: Int,
        val bottom: Int,
        val top: Int,
    ) {
        override fun toString(): String = "[$left..$right]x[$bottom..$top]"
    }

    private companion object {
        const val SCREEN_WIDTH_DP = 411
        const val BAR_PADDING_DP = 16
        const val MAP_BOTTOM_BAR_HEIGHT_DP = 58
        const val SHEET_CHEVRON_TARGET_DP = 48
        const val ARC_BOTTOM_CLEARANCE_DP = 120

        // Mirrors MapControlBar's private 4 dp stack gap; the model must stay
        // in step with the shell if that gap changes.
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/app/MapScreen.kt"
        const val ARC_STACK_GAP_DP = 4
        const val LAYERS_HALF_WIDTH_DP = 32
        const val LAYERS_HEIGHT_DP = 48
        const val LAYERS_GAP_DP = 8
        const val CREDIT_LOGO_GAP_DP = 8

        // The credit sits above the bar's reach and, in arc mode, above the
        // centred Layers target: its 48 dp height plus an 8 dp gap.
        const val MAP_CREDIT_CLEARANCE_DP = 12
        const val ATTRIBUTION_CLEARANCE_DP = 4
        const val ATTRIBUTION_MARGIN_DP = 4
        const val CREDIT_WIDTH_DP = 190
        const val CREDIT_HEIGHT_DP = 24

        // The engine's mark is drawn about 88x43 dp; bound it generously.
        const val LOGO_WIDTH_DP = 96
        const val LOGO_HEIGHT_DP = 48

        // Conservative glass box: the drawn value is narrower and shorter.
        const val SCALE_BAR_WIDTH_DP = 120
        const val SCALE_BAR_HEIGHT_DP = 40
    }
}
