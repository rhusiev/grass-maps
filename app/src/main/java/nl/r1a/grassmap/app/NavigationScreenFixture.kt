package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.r1a.grassmap.alerts.AlertBanner
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.ui.shell.NavigationSheet

/**
 * One deterministic navigation screen fixture (T-0320).
 *
 * The screen renders the same values a live state produces, but the state, the
 * speed and the banners come from this fixture instead of the follower. The
 * debug-only `core.nav.show_screen` command fills it for the golden harness and
 * the emulator acceptance; a release build never writes it and the live
 * navigation state stays the only source.
 */
internal data class NavigationScreenFixture(
    val state: NavigationState,
    val speedCurrentMetersPerSecond: Double? = null,
    val speedAverageMetersPerSecond: Double? = null,
    val speedMaxMetersPerSecond: Double? = null,
    val alerts: List<AlertBanner> = emptyList(),
    val destination: String? = null,
    val nextStopName: String? = null,
    val signalLost: Boolean = false,
    val sheet: NavigationSheet = NavigationSheet.Collapsed,
    val dark: Boolean = false,
)

/** The debug seam that shows a fixture on the navigation screen. */
internal class NavigationScreenFixtureStore {
    private val mutableState = MutableStateFlow<NavigationScreenFixture?>(null)

    /** The fixture to draw, or null when the live state is the only source. */
    val state: StateFlow<NavigationScreenFixture?> = mutableState.asStateFlow()

    fun show(fixture: NavigationScreenFixture) {
        mutableState.value = fixture
    }

    fun clear() {
        mutableState.value = null
    }
}
