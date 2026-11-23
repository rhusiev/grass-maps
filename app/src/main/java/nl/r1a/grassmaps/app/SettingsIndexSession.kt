package nl.r1a.grassmaps.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the Settings index is open over the map (T-0612). Process-scoped like
 * the other screens, so a rebuilt composition keeps it. The map's profile
 * controls open it; the rows hand off to the existing screen sessions.
 */
object SettingsIndexSession {
    private val visibleState = MutableStateFlow(false)

    val visible: StateFlow<Boolean> = visibleState.asStateFlow()

    fun show() {
        visibleState.value = true
    }

    fun hide() {
        visibleState.value = false
    }
}
