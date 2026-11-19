package nl.r1a.grassmap.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/** The collector that tells the map credit how high the bottom bars reach. */
class BottomChromeTest {
    @Test
    fun theInsetIsTheHighestReportedReach() {
        val chrome = BottomChrome()

        chrome.report(BAR_KEY, BAR_REACH_PX)
        chrome.report(SHEET_KEY, SHEET_REACH_PX)

        assertEquals(SHEET_REACH_PX, chrome.insetPx)
    }

    @Test
    fun aForgottenElementNoLongerCounts() {
        val chrome = BottomChrome()
        chrome.report(BAR_KEY, BAR_REACH_PX)
        chrome.report(SHEET_KEY, SHEET_REACH_PX)

        chrome.forget(SHEET_KEY)

        assertEquals(BAR_REACH_PX, chrome.insetPx)
    }

    @Test
    fun anElementsOwnReachIsReadApartFromTheHigherOnes() {
        val chrome = BottomChrome()
        chrome.report(SHEET_KEY, SHEET_REACH_PX)
        chrome.report(BAR_KEY, BAR_REACH_PX)

        assertEquals(BAR_REACH_PX, chrome.reachOf(BAR_KEY))
        assertEquals(0, chrome.reachOf("unreported"))
    }

    @Test
    fun nothingReportedMeansNoInset() {
        assertEquals(0, BottomChrome().insetPx)
    }

    @Test
    fun anOpenSheetRaisesTheClearanceAboveTheNavBar() {
        val chrome = BottomChrome()
        chrome.report(BOTTOM_SHEET_KEY, SHEET_REACH_PX)

        assertEquals(SHEET_REACH_PX, bottomClearancePx(NAV_BAR_PX, chrome.reachOf(BOTTOM_SHEET_KEY)))
    }

    @Test
    fun noOpenSheetKeepsTheNavigationBarClearance() {
        assertEquals(NAV_BAR_PX, bottomClearancePx(NAV_BAR_PX, BottomChrome().reachOf(BOTTOM_SHEET_KEY)))
        assertEquals(0, bottomClearancePx(0, BottomChrome().reachOf(BOTTOM_SHEET_KEY)))
    }

    private companion object {
        const val BAR_KEY = "bar"
        const val SHEET_KEY = "sheet"
        const val BAR_REACH_PX = 165
        const val SHEET_REACH_PX = 640
        const val NAV_BAR_PX = 48
    }
}
