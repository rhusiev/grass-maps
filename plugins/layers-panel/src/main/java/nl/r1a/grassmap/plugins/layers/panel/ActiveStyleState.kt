package nl.r1a.grassmap.plugins.layers.panel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * The map styles a user can pick. The UI names are Meadow and Trail. The ids
 * stay `standard` and `touring` until the rename task (T-0617).
 */
enum class MapStyleChoice(
    val styleId: String,
) {
    STANDARD("standard"),
    TOURING("touring"),
    ;

    companion object {
        /** The choice for a stored or requested [styleId], or null when unknown. */
        fun fromStyleId(styleId: String?): MapStyleChoice? = entries.firstOrNull { it.styleId == styleId }
    }
}

/**
 * The style the map shows. The layers panel calls [select], and the map
 * collects [active] and applies the matching style in place.
 */
class ActiveStyleState(
    initial: MapStyleChoice = MapStyleChoice.STANDARD,
) {
    private val state = MutableStateFlow(initial)

    val active: StateFlow<MapStyleChoice> = state.asStateFlow()

    /** Makes [choice] the active style. Returns true when it changed. */
    fun select(choice: MapStyleChoice): Boolean = state.getAndUpdate { choice } != choice
}
