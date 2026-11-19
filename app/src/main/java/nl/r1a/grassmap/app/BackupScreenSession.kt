package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the backup screen is open over the map (T-0608). Process-scoped like
 * the other interim screens; the debug control channel opens it until the
 * settings UI exists.
 */
object BackupScreenSession {
    private val visibleState = MutableStateFlow(false)

    val visible: StateFlow<Boolean> = visibleState.asStateFlow()

    fun show() {
        visibleState.value = true
    }

    fun hide() {
        visibleState.value = false
    }
}
