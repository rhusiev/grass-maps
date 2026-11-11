package nl.r1a.grassmap.plugin.api.model

import kotlinx.serialization.Serializable

/**
 * The four point orders the favourites screen offers (T-0214).
 *
 * The order is fixed and locale-independent so one library draws the same rows
 * on every device. [NAME_ASCENDING], [NAME_DESCENDING] and [LIST_THEN_NAME]
 * compare names after lowercasing and then by the JVM's ordinary string order
 * (UTF-16 code units); no locale collator takes part. Equal names keep the
 * library's file order, so a sort is stable.
 *
 * [INSERTION] is the order the merged favourites file stores the points in.
 * [LIST_THEN_NAME] puts points with no list first, then compares list name,
 * point name and file order.
 */
@Serializable
enum class FavouriteSort(
    val wireName: String,
) {
    INSERTION("insertion"),
    NAME_ASCENDING("name_ascending"),
    NAME_DESCENDING("name_descending"),
    LIST_THEN_NAME("list_then_name"),
    ;

    companion object {
        /** Parses the command wire name; an unknown value returns null. */
        fun fromWire(value: String): FavouriteSort? = entries.firstOrNull { sort -> sort.wireName == value.trim().lowercase() }
    }
}

/** Why one point matched a search. [NONE] means the query was blank. */
@Serializable
enum class FavouriteMatch {
    NONE,
    NAME,
    DESCRIPTION,
    BOTH,
}

/**
 * One rendered browse row (T-0214).
 *
 * [description] is the sanitised plain text the screen draws, never the raw
 * stored value, so a markup-carrying description cannot reach the UI as HTML.
 *
 * [position] is carried through from [FavouritePointSummary] (T-0304) so a
 * row a plugin resolves can become a route stop without reading the app's
 * store.
 */
@Serializable
data class FavouritePointRow(
    val id: String,
    val name: String,
    val list: String? = null,
    val special: FavouriteSpecial? = null,
    val description: String? = null,
    val match: FavouriteMatch = FavouriteMatch.NONE,
    val position: GeoPoint? = null,
)

/**
 * The browsed view of the library published inside [FavouriteListsState].
 *
 * [query] and [sort] are the current screen controls. [rows] is the result of
 * applying the trimmed query to the point names and rendered descriptions and
 * then the chosen sort; a blank query keeps every point.
 */
@Serializable
data class FavouriteBrowseState(
    val query: String = "",
    val sort: FavouriteSort = FavouriteSort.INSERTION,
    val rows: List<FavouritePointRow> = emptyList(),
)

/**
 * The planner stop a browse row denotes, or null when the row carries no point
 * (T-0304). `plugins/route-planner` can therefore resolve a favourite without
 * reading the app's own `Favourite` record.
 */
fun FavouritePointRow.toRouteStop(): RoutePlanPoint? =
    position?.let { point ->
        RoutePlanPoint(position = point, label = name)
    }
