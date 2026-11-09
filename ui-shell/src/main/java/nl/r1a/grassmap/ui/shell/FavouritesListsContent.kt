package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.FavouriteListsState
import nl.r1a.grassmap.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmap.plugin.api.service.Strings

/** One list row: its name, swatch colour, icon and member count. */
data class FavouritesListRow(
    val name: String,
    val color: Long?,
    val icon: String?,
    val pointCount: Int,
)

/** One point row: its list and its Home or Work label when it has one. */
data class FavouritesPointRow(
    val id: String,
    val name: String,
    val list: String,
    val special: String?,
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
                    special =
                        when (point.special) {
                            FavouriteSpecial.HOME -> strings.resolve(HOME_KEY)
                            FavouriteSpecial.WORK -> strings.resolve(WORK_KEY)
                            null -> null
                        },
                )
            },
    )

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
internal const val UNDO_KEY = "favourites_lists_undo"
internal const val EMPTY_KEY = "favourites_lists_empty"
internal const val NO_LIST_KEY = "favourites_lists_no_list"
internal const val HOME_KEY = "favourites_lists_home"
internal const val WORK_KEY = "favourites_lists_work"

private const val RGB_HEX_LENGTH = 6
private const val ARGB_HEX_LENGTH = 8
private const val HEX_RADIX = 16
private const val HEX_DIGITS = "0123456789abcdefABCDEF"
