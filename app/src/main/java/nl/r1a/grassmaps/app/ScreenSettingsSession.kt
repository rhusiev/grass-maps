package nl.r1a.grassmaps.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the theme and screen settings page is open over the map (T-0111).
 * Process-scoped like the other screens, so a rebuilt composition keeps it.
 * The debug control channel is the entry point until the map has a button.
 */
object ScreenSettingsSession {
    private val visibleState = MutableStateFlow(false)

    val visible: StateFlow<Boolean> = visibleState.asStateFlow()

    fun show() {
        visibleState.value = true
    }

    fun hide() {
        visibleState.value = false
    }
}
