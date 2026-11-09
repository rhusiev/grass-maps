package nl.r1a.grassmap.plugin.api.model

import kotlinx.serialization.Serializable

/**
 * The Home or Work special place (T-0213). A library holds at most one
 * favourite for each, so setting a new one clears the previous holder.
 */
@Serializable
enum class FavouriteSpecial {
    HOME,
    WORK,
}

/** One list as the UI draws it: its name, appearance and member count. */
@Serializable
data class FavouriteListSummary(
    val name: String,
    val color: String,
    val icon: String? = null,
    val pointCount: Int = 0,
)

/**
 * One favourite as the lists screen draws it. [list] is null for a point that
 * is not in a list, and [special] marks a Home or Work point.
 */
@Serializable
data class FavouritePointSummary(
    val id: String,
    val name: String,
    val list: String? = null,
    val special: FavouriteSpecial? = null,
)

/**
 * The lists screen state published by `plugins/favourites-ui`.
 *
 * [undoAvailable] is owned by the plugin's scoped receipt stack; the library's
 * own state does not know how many actions the screen has left to undo.
 */
@Serializable
data class FavouriteListsState(
    val lists: List<FavouriteListSummary> = emptyList(),
    val points: List<FavouritePointSummary> = emptyList(),
    val undoAvailable: Boolean = false,
)
