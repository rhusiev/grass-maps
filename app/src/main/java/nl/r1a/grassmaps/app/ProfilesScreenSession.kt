package nl.r1a.grassmaps.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.r1a.grassmaps.plugins.profiles.ui.ProfilesPage

/**
 * The page of the profiles screen over the map, or null when it is closed
 * (T-0602).
 *
 * Process-scoped like the downloads screen, so a rebuilt composition keeps it.
 * The profiles, the groups and the undo action come from the `profiles.ui`
 * stream. The debug control channel opens it until the map gets its own entry
 * point.
 */
object ProfilesScreenSession {
    private val pageState = MutableStateFlow<ProfilesPage?>(null)

    val page: StateFlow<ProfilesPage?> = pageState.asStateFlow()

    fun show() {
        pageState.value = ProfilesPage.List
    }

    fun navigate(page: ProfilesPage?) {
        pageState.value = page
    }

    fun hide() {
        pageState.value = null
    }
}
