package nl.r1a.grassmaps.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the plugin manager is open over the map (T-0607).
 *
 * Process-scoped like [DownloadsScreenSession]. The rows come from the host's
 * plugin state; this only carries the screen's visibility. The debug control
 * channel opens it until the map gets its own entry point.
 */
object PluginManagerScreenSession {
    private val visibleState = MutableStateFlow(false)

    val visible: StateFlow<Boolean> = visibleState.asStateFlow()

    fun show() {
        visibleState.value = true
    }

    fun hide() {
        visibleState.value = false
    }
}
