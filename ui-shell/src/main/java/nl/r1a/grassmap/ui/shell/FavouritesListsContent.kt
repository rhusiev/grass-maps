package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.FavouriteListsState
import nl.r1a.grassmap.plugin.api.model.FavouriteSort
import nl.r1a.grassmap.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmap.plugin.api.service.Strings

/** One list row: its name, swatch colour, icon and member count. */
data class FavouritesListRow(
    val name: String,
    val color: Long?,
    val icon: String?,
    val pointCount: Int,
)

/**
 * One point row: its list, its Home or Work label when it has one, and its
 * sanitised description when the note is not empty (T-0214).
 */
data class FavouritesPointRow(
    val id: String,
    val name: String,
    val list: String,
    val special: String?,
    val description: String? = null,
)

/** One sort choice the control offers, with its resolved label. */
data class FavouritesSortRow(
    val sort: FavouriteSort,
    val label: String,
    val selected: Boolean,
)

/**
 * The browsing controls and filtered rows of T-0214, with every label resolved.
 *
 * The plugin publishes the already filtered, sorted and rendered rows; this
 * resolver only turns labels and specials into the active language. A text-only
 * description arrives as plain text and draws exactly as before.
 */
data class FavouritesBrowseContent(
    val search: String,
    val searchHint: String,
    val sort: String,
    val sorts: List<FavouritesSortRow>,
    val points: List<FavouritesPointRow>,
    val noMatches: String?,
)

/**
 * The lists sheet with every label resolved (T-0213).
 *
 * The sheet is a shell screen over `favourites.ui`'s stream; this resolver keeps
 * the language decision out of the composable so a JVM test can prove each row
 * uses the active language and that a point with no list still reads sensibly.
 */
data class FavouritesListsContent(
    val title: String,
    val close: String,
    val undo: String,
    val undoAvailable: Boolean,
    val empty: String?,
    val lists: List<FavouritesListRow>,
    val points: List<FavouritesPointRow>,
)

fun favouritesListsContent(
    state: FavouriteListsState,
    strings: Strings,
): FavouritesListsContent =
    FavouritesListsContent(
        title = strings.resolve(TITLE_KEY),
        close = strings.resolve(CLOSE_KEY),
        undo = strings.resolve(UNDO_KEY),
        undoAvailable = state.undoAvailable,
        empty = if (state.lists.isEmpty()) strings.resolve(EMPTY_KEY) else null,
        lists =
            state.lists.map { list ->
                FavouritesListRow(
                    name = list.name,
                    color = favouriteColour(list.color),
                    icon = list.icon,
                    pointCount = list.pointCount,
                )
            },
        points =
            state.points.map { point ->
                FavouritesPointRow(
                    id = point.id,
                    name = point.name,
                    list = point.list ?: strings.resolve(NO_LIST_KEY),
                    special = specialLabel(point.special, strings),
                )
            },
    )

/**
 * Resolves the T-0214 browse controls and the derived rows. The plugin already
 * applied the query, the sort and the safe description renderer; this keeps the
 * shell free of markup and matching rules.
 */
fun favouritesBrowseContent(
    state: FavouriteListsState,
    strings: Strings,
): FavouritesBrowseContent {
    val browse = state.browse
    return FavouritesBrowseContent(
        search = strings.resolve(SEARCH_KEY),
        searchHint = strings.resolve(SEARCH_HINT_KEY),
        sort = strings.resolve(SORT_KEY),
        sorts =
            FavouriteSort.entries.map { sort ->
                FavouritesSortRow(
                    sort = sort,
                    label = strings.resolve(favouriteSortKey(sort)),
                    selected = sort == browse.sort,
                )
            },
        points =
            browse.rows.map { row ->
                FavouritesPointRow(
                    id = row.id,
                    name = row.name,
                    list = row.list ?: strings.resolve(NO_LIST_KEY),
                    special = specialLabel(row.special, strings),
                    description = row.description,
                )
            },
        noMatches = if (browse.rows.isEmpty() && state.points.isNotEmpty()) strings.resolve(NO_MATCHES_KEY) else null,
    )
}

private fun specialLabel(
    special: FavouriteSpecial?,
    strings: Strings,
): String? =
    when (special) {
        FavouriteSpecial.HOME -> strings.resolve(HOME_KEY)
        FavouriteSpecial.WORK -> strings.resolve(WORK_KEY)
        null -> null
    }

internal fun favouriteSortKey(sort: FavouriteSort): String =
    when (sort) {
        FavouriteSort.INSERTION -> SORT_INSERTION_KEY
        FavouriteSort.NAME_ASCENDING -> SORT_NAME_ASCENDING_KEY
        FavouriteSort.NAME_DESCENDING -> SORT_NAME_DESCENDING_KEY
        FavouriteSort.LIST_THEN_NAME -> SORT_LIST_THEN_NAME_KEY
    }

/**
 * Parses an OsmAnd colour (`#RRGGBB` or `#AARRGGBB`) into a Compose ARGB value.
 * An unknown form returns null, so the sheet draws a neutral swatch instead of
 * guessing.
 */
internal fun favouriteColour(value: String): Long? {
    val text = value.trim().removePrefix("#")
    if (text.length != RGB_HEX_LENGTH && text.length != ARGB_HEX_LENGTH) {
        return null
    }
    if (text.any { character -> character !in HEX_DIGITS }) {
        return null
    }
    val argb = if (text.length == RGB_HEX_LENGTH) "FF$text" else text
    return argb.toLongOrNull(HEX_RADIX)
}

internal const val TITLE_KEY = "favourites_lists_title"
internal const val CLOSE_KEY = "favourites_lists_close"
internal const val UNDO_KEY = "favourites_lists_undo"
internal const val EMPTY_KEY = "favourites_lists_empty"
internal const val NO_LIST_KEY = "favourites_lists_no_list"
internal const val HOME_KEY = "favourites_lists_home"
internal const val WORK_KEY = "favourites_lists_work"
internal const val SEARCH_KEY = "favourites_lists_search"
internal const val SEARCH_HINT_KEY = "favourites_lists_search_hint"
internal const val SORT_KEY = "favourites_lists_sort"
internal const val SORT_INSERTION_KEY = "favourites_lists_sort_insertion"
internal const val SORT_NAME_ASCENDING_KEY = "favourites_lists_sort_name_ascending"
internal const val SORT_NAME_DESCENDING_KEY = "favourites_lists_sort_name_descending"
internal const val SORT_LIST_THEN_NAME_KEY = "favourites_lists_sort_list_then_name"
internal const val NO_MATCHES_KEY = "favourites_lists_no_matches"

private const val RGB_HEX_LENGTH = 6
private const val ARGB_HEX_LENGTH = 8
private const val HEX_RADIX = 16
private const val HEX_DIGITS = "0123456789abcdefABCDEF"
