package nl.r1a.grassmap.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The space the library's rows reserve below the last row for the overlay bars.
 * The bars sit on the bottom edge and the lowest one draws its surface over the
 * window's navigation inset, so a reserve that leaves the inset out puts the
 * last row under the action bar.
 *
 * The placement itself - that the bars are anchored to the bottom edge rather
 * than the top - is not covered here. This module's tests run on the JVM and
 * cannot lay out a composable, so that half is device evidence only.
 */
class TrackLibraryBarLayoutTest {
    @Test
    fun theSelectedRowsReserveTheActionBarAndTheNavigationInset() {
        val reserve = trackLibraryBarReserveDp(selectionVisible = true, undoVisible = false, navigationInsetDp = NAV_INSET_DP)

        assertEquals(ACTION_BAR_RESERVE_DP + NAV_INSET_DP, reserve, TOLERANCE)
    }

    @Test
    fun aTallerNavigationInsetMakesTheReserveTallerByTheSameAmount() {
        val withoutInset = trackLibraryBarReserveDp(selectionVisible = true, undoVisible = false, navigationInsetDp = 0f)
        val withInset = trackLibraryBarReserveDp(selectionVisible = true, undoVisible = false, navigationInsetDp = NAV_INSET_DP)

        assertEquals(NAV_INSET_DP, withInset - withoutInset, TOLERANCE)
    }

    @Test
    fun theUndoNoticeAboveTheActionBarIsReservedToo() {
        val actionBarOnly = trackLibraryBarReserveDp(selectionVisible = true, undoVisible = false, navigationInsetDp = NAV_INSET_DP)
        val both = trackLibraryBarReserveDp(selectionVisible = true, undoVisible = true, navigationInsetDp = NAV_INSET_DP)

        assertEquals(UNDO_RESERVE_DP + BAR_GAP_DP, both - actionBarOnly, TOLERANCE)
    }

    @Test
    fun theUndoNoticeAloneReservesItsOwnHeight() {
        val reserve = trackLibraryBarReserveDp(selectionVisible = false, undoVisible = true, navigationInsetDp = NAV_INSET_DP)

        assertEquals(UNDO_RESERVE_DP + NAV_INSET_DP, reserve, TOLERANCE)
    }

    @Test
    fun thePlainListReservesNothing() {
        val reserve = trackLibraryBarReserveDp(selectionVisible = false, undoVisible = false, navigationInsetDp = NAV_INSET_DP)

        assertEquals(0f, reserve, TOLERANCE)
    }

    private companion object {
        /** The 32 dp a three-button navigation bar takes on the reference phone. */
        const val NAV_INSET_DP = 32f
        const val ACTION_BAR_RESERVE_DP = 96f
        const val UNDO_RESERVE_DP = 72f
        const val BAR_GAP_DP = 8f
        const val TOLERANCE = 0.001f
    }
}
