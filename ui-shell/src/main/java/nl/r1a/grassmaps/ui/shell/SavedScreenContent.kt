package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.FavouriteListsState
import nl.r1a.grassmaps.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmaps.plugin.api.service.Strings

/** The two segments of the Saved screen (round 9 `k-saved`). */
enum class SavedSegment {
    PLACES,
    TRACKS,
}

/** The four bottom-bar actions in the canon's fixed order. */
enum class SavedBarAction {
    SEARCH,
    MAP,
    SORT,
    ADD,
}

/**
 * The commands the caller can actually send. The derivation hides an action
 * whose command is absent instead of drawing a dead control.
 */
data class SavedCommands(
    val search: Boolean,
    val map: Boolean,
    val sort: Boolean,
    val add: Boolean,
    val visibility: Boolean,
)

data class SavedSegmentRow(
    val segment: SavedSegment,
    val label: String,
    val selected: Boolean,
)

data class SavedBarActionRow(
    val action: SavedBarAction,
    val label: String,
)

/** One expanded place row under a group. */
data class SavedPlaceRow(
    val name: String,
    val detail: String,
    val special: String?,
)

/** One group, its visibility state and the places shown when it is expanded. */
data class SavedGroupRow(
    val name: String,
    val color: Long?,
    val shown: Boolean,
    val canToggleVisibility: Boolean,
    val visibilityLabel: String,
    val summary: String,
    val places: List<SavedPlaceRow>,
    val more: Int,
    val moreLabel: String?,
)

/** The whole Saved section, with every label resolved for the active language. */
data class SavedScreenContent(
    val title: String,
    val subtitle: String,
    val hint: String,
    val onMapSummary: String,
    val groups: List<SavedGroupRow>,
    val segments: List<SavedSegmentRow>,
    val actions: List<SavedBarActionRow>,
    val empty: String?,
    val tracksNotice: String,
)

/**
 * Derives the Saved screen from the one favourites state. The plugin owns the
 * lists and the browse view; the caller supplies [hiddenGroups] from the
 * persisted layer settings. This resolver groups the points, applies that
 * visibility and resolves labels. It never reads a file or a GPX.
 */
fun savedScreenContent(
    state: FavouriteListsState,
    strings: Strings,
    commands: SavedCommands,
    hiddenGroups: Set<String> = emptySet(),
    selectedSegment: SavedSegment = SavedSegment.PLACES,
): SavedScreenContent {
    val points = pointsFor(state)
    val groups = state.lists.map { list -> savedGroup(list.name, list.color, points, strings, commands, hiddenGroups) }
    val placeCount = state.points.size
    val groupCount = state.lists.size
    val visibleCount = state.lists.count { it.name !in hiddenGroups }
    return SavedScreenContent(
        title = strings.resolve(SAVED_TITLE_KEY),
        subtitle =
            strings.resolve(
                SAVED_SUBTITLE_KEY,
                listOf(placesPhrase(placeCount, strings), groupsPhrase(groupCount, strings)),
            ),
        hint = strings.resolve(SAVED_HINT_KEY),
        onMapSummary =
            strings.resolve(
                SAVED_ON_MAP_SUMMARY_KEY,
                listOf(visibleCount.toString(), groupCount.toString()),
            ),
        groups = groups,
        segments =
            SavedSegment.entries.map { segment ->
                SavedSegmentRow(segment, strings.resolve(savedSegmentKey(segment)), segment == selectedSegment)
            },
        actions = savedActions(commands, placeCount, strings),
        empty = if (state.lists.isEmpty()) strings.resolve(SAVED_EMPTY_KEY) else null,
        tracksNotice = strings.resolve(SAVED_TRACKS_EMPTY_KEY),
    )
}

private data class SavedPoint(
    val name: String,
    val list: String?,
    val special: FavouriteSpecial?,
)

private fun pointsFor(state: FavouriteListsState): List<SavedPoint> =
    if (state.browse.query.isBlank()) {
        state.points.map { SavedPoint(it.name, it.list, it.special) }
    } else {
        state.browse.rows.map { SavedPoint(it.name, it.list, it.special) }
    }

private fun savedGroup(
    name: String,
    color: String,
    points: List<SavedPoint>,
    strings: Strings,
    commands: SavedCommands,
    hiddenGroups: Set<String>,
): SavedGroupRow {
    val places = points.filter { it.list == name }
    val shown = name !in hiddenGroups
    val countPhrase = placesPhrase(places.size, strings)
    val summaryKey = if (shown) SAVED_GROUP_ON_MAP_KEY else SAVED_GROUP_OFF_MAP_KEY
    val visibilityKey = if (shown) SAVED_HIDE_GROUP_KEY else SAVED_SHOW_GROUP_KEY
    val more = (places.size - SAVED_PLACE_PREVIEW).coerceAtLeast(0)
    return SavedGroupRow(
        name = name,
        color = favouriteColour(color),
        shown = shown,
        canToggleVisibility = commands.visibility,
        visibilityLabel = strings.resolve(visibilityKey, listOf(name)),
        summary = strings.resolve(summaryKey, listOf(countPhrase)),
        places =
            places.take(SAVED_PLACE_PREVIEW).map { point ->
                SavedPlaceRow(
                    name = point.name,
                    detail = point.list ?: strings.resolve(NO_LIST_KEY),
                    special = specialLabel(point.special, strings),
                )
            },
        more = more,
        moreLabel = if (more == 0) null else strings.resolve(SAVED_MORE_KEY, listOf(more.toString(), name)),
    )
}

private fun savedActions(
    commands: SavedCommands,
    placeCount: Int,
    strings: Strings,
): List<SavedBarActionRow> =
    buildList {
        if (commands.search) add(SavedBarAction.SEARCH)
        if (commands.map) add(SavedBarAction.MAP)
        if (commands.sort) add(SavedBarAction.SORT)
        if (commands.add) add(SavedBarAction.ADD)
    }.map { action ->
        SavedBarActionRow(
            action = action,
            label =
                when (action) {
                    SavedBarAction.SEARCH -> strings.resolve(SAVED_SEARCH_KEY, listOf(placeCount.toString()))
                    SavedBarAction.MAP -> strings.resolve(SAVED_MAP_KEY)
                    SavedBarAction.SORT -> strings.resolve(SAVED_SORT_KEY)
                    SavedBarAction.ADD -> strings.resolve(SAVED_ADD_KEY)
                },
        )
    }

/**
 * The eye's click handler as data. The composable runs the returned function;
 * a JVM test can run it too and see which group and which value reach the app.
 * The persisted store is the only visibility state, so the eye only sends the
 * new value to its setter and keeps no local copy that could drift.
 */
internal fun groupVisibilityToggle(
    groupName: String,
    shown: Boolean,
    onToggleGroupShown: (String, Boolean) -> Unit,
): () -> Unit = { onToggleGroupShown(groupName, !shown) }

private fun placesPhrase(
    count: Int,
    strings: Strings,
): String = strings.resolveQuantity(SAVED_PLACES_KEY, count, listOf(count.toString()))

private fun groupsPhrase(
    count: Int,
    strings: Strings,
): String = strings.resolveQuantity(SAVED_GROUPS_KEY, count, listOf(count.toString()))

internal fun savedSegmentKey(segment: SavedSegment): String =
    when (segment) {
        SavedSegment.PLACES -> SAVED_TAB_PLACES_KEY
        SavedSegment.TRACKS -> SAVED_TAB_TRACKS_KEY
    }

internal const val SAVED_TITLE_KEY = "saved_screen_title"
internal const val SAVED_HINT_KEY = "saved_screen_hint"
internal const val SAVED_PLACES_KEY = "saved_screen_places"
internal const val SAVED_GROUPS_KEY = "saved_screen_groups"
internal const val SAVED_SUBTITLE_KEY = "saved_screen_subtitle"
internal const val SAVED_ON_MAP_SUMMARY_KEY = "saved_screen_on_map_summary"
internal const val SAVED_GROUP_ON_MAP_KEY = "saved_screen_group_on_map"
internal const val SAVED_GROUP_OFF_MAP_KEY = "saved_screen_group_off_map"
internal const val SAVED_HIDE_GROUP_KEY = "saved_screen_hide_group"
internal const val SAVED_SHOW_GROUP_KEY = "saved_screen_show_group"
internal const val SAVED_MORE_KEY = "saved_screen_more"
internal const val SAVED_TAB_PLACES_KEY = "saved_screen_tab_places"
internal const val SAVED_TAB_TRACKS_KEY = "saved_screen_tab_tracks"
internal const val SAVED_SEARCH_KEY = "saved_screen_search"
internal const val SAVED_MAP_KEY = "saved_screen_map"
internal const val SAVED_SORT_KEY = "saved_screen_sort"
internal const val SAVED_ADD_KEY = "saved_screen_add"
internal const val SAVED_TRACKS_EMPTY_KEY = "saved_screen_tracks_empty"
internal const val SAVED_EMPTY_KEY = "saved_screen_empty"

/** The canon shows a few place rows and folds the rest into a "more" line. */
private const val SAVED_PLACE_PREVIEW = 2
