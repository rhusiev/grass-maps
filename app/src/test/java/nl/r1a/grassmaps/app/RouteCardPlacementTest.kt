package nl.r1a.grassmaps.app

import androidx.compose.ui.unit.dp
import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The over-map route card must stay clear of the map control bar and must not
 * hide the widget grid (T-0612, round 9 review).
 */
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

    @Test
    fun withoutARouteCardTheWidgetsKeepTheirPlace() {
        assertEquals((BAR_PADDING_DP + WIDGET_GRID_GAP_DP + MAP_BOTTOM_BAR_HEIGHT_DP).dp, widgetGridTopInset(null))
    }

    @Test
    fun aShownRouteCardPushesTheWidgetsBelowItAtAnyFontScale() {
        // 68 dp is the ends-only card at font scale 1.0; 120 and 200 dp stand
        // for larger font scales, where the two lines wrap.
        for (cardHeight in listOf(68.dp, 120.dp, 200.dp)) {
            val cardBottom = ROUTE_CARD_TOP_DP.dp + cardHeight
            val widgetsTop = widgetGridTopInset(cardHeight)

            assertTrue(
                "card ${cardHeight.value} dp ends at ${cardBottom.value} dp, widgets start at ${widgetsTop.value} dp",
                cardBottom + WIDGET_GRID_GAP_DP.dp <= widgetsTop,
            )
            assertTrue("the widgets never move up", widgetsTop >= widgetGridTopInset(null))
        }
    }

    @Test
    fun theMapControlsGiveWayToTheReadyPreviewOnly() {
        for (status in RoutePlanStatus.entries) {
            assertEquals("status $status", status != RoutePlanStatus.Ready, mapControlsShown(navigationActive = false, status))
            assertFalse("status $status during navigation", mapControlsShown(navigationActive = true, status))
        }
    }
}
