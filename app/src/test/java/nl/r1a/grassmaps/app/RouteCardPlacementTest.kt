package nl.r1a.grassmaps.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The over-map route card must stay clear of the map control bar (T-0612). */
class RouteCardPlacementTest {
    // 1080 px wide at the Moto g32's 2.75 density, where the defect was found.
    private val screenWidth = 393.dp

    @Test
    fun theTopEndBarWidthIsReservedAtTheCardsEnd() {
        for (barWidth in listOf(44.dp, 52.dp, 96.dp)) {
            val insets = routeCardInsets(controlBarWidth = barWidth, arc = false)
            val cardRight = screenWidth - insets.end
            val barLeft = screenWidth - BAR_PADDING_DP.dp - barWidth

            assertTrue(
                "bar ${barWidth.value} dp: card ends at ${cardRight.value} dp, bar starts at ${barLeft.value} dp",
                cardRight + WIDGET_GRID_GAP_DP.dp <= barLeft,
            )
        }
    }

    @Test
    fun anArcBarIsBottomAnchoredSoTheCardKeepsSymmetricPadding() {
        val insets = routeCardInsets(controlBarWidth = 184.dp, arc = true)

        assertEquals(BAR_PADDING_DP.dp, insets.start)
        assertEquals(BAR_PADDING_DP.dp, insets.end)
    }

    @Test
    fun aWiderMeasuredBarNeverShrinksTheEndClearance() {
        val narrow = routeCardInsets(controlBarWidth = 52.dp, arc = false)
        val wide = routeCardInsets(controlBarWidth = 96.dp, arc = false)

        assertTrue("wide ${wide.end} must clear more than narrow ${narrow.end}", wide.end > narrow.end)
    }
}
