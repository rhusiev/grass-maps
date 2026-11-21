package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The round-5 navigation bar is 58 high and fully round, so its corner is 29.
 *
 * The bar is a shell constant rather than a measured value in a JVM test, so
 * these assertions stop a later edit from restoring the old 64/32 pair. The
 * screen's bottom chrome inset is derived from the same height.
 */
class NavigationBarMetricsTest {
    @Test
    fun theBarIsRoundFiveHeightAndFullyRound() {
        assertEquals(58, NAV_BAR_HEIGHT_DP)
        assertEquals(29, NAV_BAR_CORNER_DP)
        assertEquals("the bar must be fully round", NAV_BAR_HEIGHT_DP / 2, NAV_BAR_CORNER_DP)
    }
}
