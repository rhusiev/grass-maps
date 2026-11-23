package nl.r1a.grassmaps.plugins.layers.panel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import nl.r1a.grassmaps.plugin.ui.api.StyleSwatch

/**
 * The map styles a user can pick. The UI names are Meadow and Trail. The ids
 * are `meadow` and `trail`.
 */
enum class MapStyleChoice(
    val styleId: String,
) {
    MEADOW("meadow"),
    TRAIL("trail"),
    ;

    companion object {
        /** The choice for a stored or requested [styleId], or null when unknown. */
        fun fromStyleId(styleId: String?): MapStyleChoice? = entries.firstOrNull { it.styleId == styleId }
    }
}

/** The token swatch this style's row shows; no preview artwork ships with the app. */
fun MapStyleChoice.swatch(): StyleSwatch =
    when (this) {
        MEADOW -> StyleSwatch.MEADOW
        TRAIL -> StyleSwatch.TRAIL
    }

/**
 * The style the map shows. The layers panel calls [select], and the map
 * collects [active] and applies the matching style in place.
 */
class ActiveStyleState(
    initial: MapStyleChoice = MapStyleChoice.MEADOW,
) {
    private val state = MutableStateFlow(initial)

    val active: StateFlow<MapStyleChoice> = state.asStateFlow()

    /** Makes [choice] the active style. Returns true when it changed. */
    fun select(choice: MapStyleChoice): Boolean = state.getAndUpdate { choice } != choice
}
