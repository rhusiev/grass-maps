package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the track library screen is open over the map (T-0414).
 *
 * Process-scoped like the place card, so a rebuilt composition keeps it. The
 * tracks and folders come from the `track.library` stream; this only carries
 * the screen's visibility. The debug control channel opens it until the map's
 * track selection grows an entry (T-0415).
 */
object TrackLibraryScreen {
    private val visibleState = MutableStateFlow(false)

    val visible: StateFlow<Boolean> = visibleState.asStateFlow()

    fun show() {
        visibleState.value = true
    }

    fun hide() {
        visibleState.value = false
    }
}
