package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the downloads screen is open over the map (T-0504).
 *
 * Process-scoped like the lists and library sheets, so a rebuilt composition
 * keeps it. The catalogue, the rows and the transfers come from the
 * `downloads.ui` stream; this only carries the screen's visibility. The debug
 * control channel opens it until the map gets its own entry point.
 */
object DownloadsScreenSession {
    private val visibleState = MutableStateFlow(false)

    val visible: StateFlow<Boolean> = visibleState.asStateFlow()

    fun show() {
        visibleState.value = true
    }

    fun hide() {
        visibleState.value = false
    }
}
