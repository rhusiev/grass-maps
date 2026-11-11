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
 *
 * [position] is the stored point (T-0304). A planner stop needs coordinates,
 * and before this field a plugin could only read the app's own `Favourite`
 * record, which is not in the contract. It is nullable so a library entry that
 * cannot be read keeps drawing the row without becoming a route stop.
 *
 * [description] is the stored text, untrusted region data or a user's own
 * note. It may contain simple HTML, so a renderer must sanitise it before the
 * screen draws it; see `plugins/favourites-ui` (T-0214).
 */
@Serializable
data class FavouritePointSummary(
    val id: String,
    val name: String,
    val list: String? = null,
    val special: FavouriteSpecial? = null,
    val description: String? = null,
    val position: GeoPoint? = null,
)

/**
 * The lists screen state published by `plugins/favourites-ui`.
 *
 * [undoAvailable] is owned by the plugin's scoped receipt stack; the library's
 * own state does not know how many actions the screen has left to undo.
 *
 * [points] is the library's file order. [browse] is the filtered, sorted and
 * rendered view the screen draws (T-0214); it is derived on every publish and
 * never persisted.
 */
@Serializable
data class FavouriteListsState(
    val lists: List<FavouriteListSummary> = emptyList(),
    val points: List<FavouritePointSummary> = emptyList(),
    val undoAvailable: Boolean = false,
    val browse: FavouriteBrowseState = FavouriteBrowseState(),
)
