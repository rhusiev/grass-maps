package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.ui.api.NavigationScreenState
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every reachable navigation control has one spoken name in both languages.
 *
 * The map from a control to its label key is written out here on purpose: a new
 * [NavigationControl] fails the coverage assertion, and a label that is blank,
 * duplicated or a bare glyph fails the name assertions. Controls whose visible
 * text already says their name keep it; the entry point left-hand side is a
 * label key only where the control is a glyph or a chevron.
 */
class NavigationControlLabelTest {
    private val english = TestStringCatalog.read(ENGLISH)
    private val ukrainian = TestStringCatalog.read(UKRAINIAN)

    @Test
    fun everyControlHasOneSpokenName() {
        val labels = labels()
        val missing = NavigationControl.entries.filter { it !in labels && it !in READ_ONLY }
        assertTrue("controls without a spoken name: $missing", missing.isEmpty())

        for ((control, key) in labels) {
            val name = english.resolver().resolve(key)
            val translated = ukrainian.resolver().resolve(key)
            assertTrue("$control is named '$name'", name.isNotBlank() && name != key)
            assertTrue("$control is blank in Ukrainian", translated.isNotBlank() && translated != key)
            assertFalse("$control reads as a generic button", name.equals("Button", ignoreCase = true))
            assertTrue(
                "$control reads as its glyph: '$name'",
                name.any { it.isLetter() } && name.length > 1,
            )
        }
    }

    @Test
    fun noTwoControlsShareASpokenName() {
        val byName =
            labels()
                .entries
                .groupBy { english.resolver().resolve(it.value) }
                .filterValues { it.size > 1 }
        assertTrue("controls that say the same name: $byName", byName.isEmpty())
    }

    @Test
    fun everyReachableControlIsInTheList() {
        val collapsed =
            navigationControls(
                state(NavigationScreenStatus.Following),
                NavigationSheet.Collapsed,
            )
        val trip = navigationControls(state(NavigationScreenStatus.Following), NavigationSheet.Trip)
        val settings = navigationControls(state(NavigationScreenStatus.Following), NavigationSheet.Settings)
        val arrived = navigationControls(state(NavigationScreenStatus.Arrived), NavigationSheet.Collapsed)

        for (control in collapsed + trip + settings + arrived) {
            if (control in READ_ONLY) {
                continue
            }
            assertNotNull("$control has no spoken name", labels()[control])
        }
    }

    private fun labels(): Map<NavigationControl, String> =
        mapOf(
            NavigationControl.End to NavigationScreenStrings.END,
            NavigationControl.Expand to NavigationScreenStrings.EXPAND,
            NavigationControl.Collapse to NavigationScreenStrings.COLLAPSE,
            NavigationControl.Recentre to NavigationScreenStrings.RECENTER,
            NavigationControl.Camera to NavigationScreenStrings.CAMERA,
            NavigationControl.Alerts to NavigationScreenStrings.ALERTS,
            NavigationControl.Back to NavigationScreenStrings.BACK,
            NavigationControl.ResetToProfile to NavigationScreenStrings.RESET,
            NavigationControl.AutoZoom to NavigationScreenStrings.AUTO_ZOOM,
            NavigationControl.AutoCentre to NavigationScreenStrings.AUTO_CENTRE,
            NavigationControl.Tilt to NavigationScreenStrings.THREE_D,
            NavigationControl.AlertSignal to NavigationScreenStrings.ALERT_SIGNAL,
            NavigationControl.AlertReroute to NavigationScreenStrings.ALERT_REROUTE,
            NavigationControl.AlertOffRoute to NavigationScreenStrings.ALERT_OFF_ROUTE,
            NavigationControl.AlertArrival to NavigationScreenStrings.ALERT_ARRIVAL,
            NavigationControl.AlertTunnel to NavigationScreenStrings.ALERT_TUNNEL,
            NavigationControl.AlertCrossing to NavigationScreenStrings.ALERT_CROSSING,
            NavigationControl.AlertHazard to NavigationScreenStrings.ALERT_HAZARD,
            NavigationControl.Pause to NavigationScreenStrings.PAUSE,
            NavigationControl.Resume to NavigationScreenStrings.RESUME,
            NavigationControl.Done to NavigationScreenStrings.DONE,
        )

    private fun state(status: NavigationScreenStatus) = NavigationScreenState(status = status)

    private companion object {
        /** Read-only read-outs the reachability set carries beside the controls. */
        val READ_ONLY = setOf(NavigationControl.Eta)

        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
    }
}
