package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.ui.api.NavigationScreenState
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation screen's reachable controls (T-0320).
 *
 * Every control the screen draws is in this set for the state and sheet it is
 * drawn in, so a control that is visible cannot be unreachable in the test and
 * a control the design removed cannot linger.
 */
class NavigationScreenControlsTest {
    @Test
    fun theFollowingBarHasEndEtaAndExpandOnly() {
        val controls = navigationControls(state(), NavigationSheet.Collapsed)

        assertTrue(controls.containsAll(setOf(NavigationControl.End, NavigationControl.Eta, NavigationControl.Expand)))
        assertFalse(controls.contains(NavigationControl.Pause))
        assertFalse(controls.contains(NavigationControl.Done))
        assertFalse(controls.contains(NavigationControl.Recentre))
    }

    @Test
    fun aPanAddsTheRecentreControl() {
        val controls = navigationControls(state().copy(recentreVisible = true), NavigationSheet.Collapsed)

        assertTrue(controls.contains(NavigationControl.Recentre))
    }

    @Test
    fun pausedPutsResumeInTheBarInsteadOfPause() {
        val controls = navigationControls(state(), NavigationSheet.Collapsed)
        val paused = navigationControls(state(paused = true), NavigationSheet.Collapsed)

        assertTrue(paused.contains(NavigationControl.Resume))
        assertFalse(paused.contains(NavigationControl.Pause))
        assertTrue(controls.contains(NavigationControl.Eta))
    }

    @Test
    fun theExpandedCardKeepsEndPauseCollapseAndCameraAndAlerts() {
        val controls = navigationControls(state(), NavigationSheet.Trip)

        assertTrue(
            controls.containsAll(
                setOf(
                    NavigationControl.End,
                    NavigationControl.Pause,
                    NavigationControl.Collapse,
                    NavigationControl.CameraAndAlerts,
                ),
            ),
        )
        assertFalse(controls.contains(NavigationControl.Eta))
        val paused = navigationControls(state(paused = true), NavigationSheet.Trip)
        assertTrue(paused.contains(NavigationControl.Resume))
        assertFalse(paused.contains(NavigationControl.Pause))
    }

    @Test
    fun arrivalLeavesDoneAlone() {
        val arrived = state(arrived = true)
        for (sheet in NavigationSheet.entries) {
            assertEquals(setOf(NavigationControl.Done), navigationControls(arrived, sheet))
        }
    }

    @Test
    fun theSettingsSheetReachesEveryCameraAndAlertSwitch() {
        val controls = navigationControls(state(), NavigationSheet.Settings)

        assertTrue(
            controls.containsAll(
                setOf(
                    NavigationControl.Back,
                    NavigationControl.ResetToProfile,
                    NavigationControl.AutoZoom,
                    NavigationControl.AutoCentre,
                    NavigationControl.Tilt,
                ),
            ),
        )
        for (switch in NavigationAlertSwitch.entries) {
            assertTrue("$switch is unreachable", controls.contains(alertControl(switch)))
        }
        assertFalse(controls.contains(NavigationControl.End))
    }

    private fun state(
        paused: Boolean = false,
        arrived: Boolean = false,
    ): NavigationScreenState =
        NavigationScreenState(
            status =
                when {
                    arrived -> NavigationScreenStatus.Arrived
                    paused -> NavigationScreenStatus.Paused
                    else -> NavigationScreenStatus.Following
                },
        )
}
