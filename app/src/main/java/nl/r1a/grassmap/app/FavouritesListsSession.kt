package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the lists sheet is open over the map (T-0213).
 *
 * Process-scoped like the place card, so a rebuilt composition keeps it. The
 * lists themselves come from the `favourites.ui` stream; this only carries the
 * sheet's visibility. The debug control channel opens it until a favourites
 * entry point exists in the main UI.
 */
object FavouritesListsSession {
    private val visibleState = MutableStateFlow(false)

    val visible: StateFlow<Boolean> = visibleState.asStateFlow()

    fun show() {
        visibleState.value = true
    }

    fun hide() {
        visibleState.value = false
    }
}
