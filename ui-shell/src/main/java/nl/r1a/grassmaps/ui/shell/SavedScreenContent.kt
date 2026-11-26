package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.FavouriteListsState
import nl.r1a.grassmaps.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
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

/**
 * One place row under a group. [detail] is the rendered description, or null
 * when the place has none; [special] is the Home or Work label.
 */
data class SavedPlaceRow(
    val id: String,
    val name: String,
    val detail: String?,
    val special: String?,
    val position: GeoPoint? = null,
)

/**
 * One group, its visibility state and its places. [list] is the stored group
 * name, or null for the places that have no group: the map always draws those,
 * so that row has no eye. Only a declared list can be recoloured.
 *
 * [places] are the first rows the canon shows; [remaining] are the rest, which
 * the "more" line opens.
 */
data class SavedGroupRow(
    val name: String,
    val list: String?,
    val color: Long?,
    val shown: Boolean,
    val canToggleVisibility: Boolean,
    val canRecolour: Boolean,
    val visibilityLabel: String?,
    val recolourLabel: String?,
    val summary: String,
    val places: List<SavedPlaceRow>,
    val remaining: List<SavedPlaceRow>,
    val more: Int,
    val moreLabel: String?,
)

/**
 * The whole Saved section, with every label resolved for the active language.
 * [hint] names the sort the rows really use. [onMapSummary] is null when no
 * place has a group, because there is no group to count.
 */
data class SavedScreenContent(
    val title: String,
    val subtitle: String,
    val hint: String,
    val onMapSummary: String?,
    val groups: List<SavedGroupRow>,
    val segments: List<SavedSegmentRow>,
    val actions: List<SavedBarActionRow>,
    val empty: String?,
    val noMatches: String?,
    val searching: Boolean,
    val tracksNotice: String,
)

/**
 * Derives the Saved screen from the one favourites state. The plugin owns the
 * lists and the browse view; the caller supplies [hiddenGroups] from the
 * persisted layer settings. This resolver groups the browse rows, applies that
 * visibility and resolves labels. It never reads a file or a GPX.
 *
 * The groups are the declared lists in their stored order, then every group
 * name a place carries without a definition, then one row for the places with
 * no group. The rows inside a group keep the plugin's sort and search.
 */
fun savedScreenContent(
    state: FavouriteListsState,
    strings: Strings,
    commands: SavedCommands,
    hiddenGroups: Set<String> = emptySet(),
    selectedSegment: SavedSegment = SavedSegment.PLACES,
): SavedScreenContent {
    val searching = state.browse.query.isNotBlank()
    val rows = browseRows(state)
    val declared = state.lists.map { it.name }.toSet()
    val names = savedGroupNames(state)
    val named =
        names.map { name ->
            val list = state.lists.firstOrNull { it.name == name }
            savedGroup(
                SavedGroupKey(name, list?.color, canRecolour = name in declared),
                rows,
                strings,
                commands,
                hiddenGroups,
            )
        }
    val ungrouped = rows.any { it.list == null }
    val groups =
        (named + if (ungrouped) listOf(ungroupedRow(rows, strings)) else emptyList())
            .filter { group -> !searching || group.places.isNotEmpty() }
    val placeCount = state.points.size
    val visibleCount = names.count { it !in hiddenGroups }
    return SavedScreenContent(
        title = strings.resolve(SAVED_TITLE_KEY),
        subtitle = subtitle(placeCount, names.size, strings),
        hint = strings.resolve(favouriteSortKey(state.browse.sort)),
        onMapSummary =
            if (names.isEmpty()) {
                null
            } else {
                strings.resolve(SAVED_ON_MAP_SUMMARY_KEY, listOf(visibleCount.toString(), names.size.toString()))
            },
        groups = groups,
        segments =
            SavedSegment.entries.map { segment ->
                SavedSegmentRow(segment, strings.resolve(savedSegmentKey(segment)), segment == selectedSegment)
            },
        actions = savedActions(commands, placeCount, strings),
        empty = if (state.points.isEmpty()) strings.resolve(SAVED_EMPTY_KEY) else null,
        noMatches = if (searching && rows.isEmpty() && state.points.isNotEmpty()) strings.resolve(NO_MATCHES_KEY) else null,
        searching = searching,
        tracksNotice = strings.resolve(SAVED_TRACKS_EMPTY_KEY),
    )
}

/**
 * Every group name the library knows: declared lists first, then the names
 * places carry without a definition, in file order.
 */
internal fun savedGroupNames(state: FavouriteListsState): List<String> =
    (state.lists.map { it.name } + state.points.mapNotNull { it.list }).distinct()

private data class SavedPoint(
    val id: String,
    val name: String,
    val list: String?,
    val special: FavouriteSpecial?,
    val description: String?,
    val position: GeoPoint?,
)

private data class SavedGroupKey(
    val name: String,
    val color: String?,
    val canRecolour: Boolean,
)

/**
 * The plugin's filtered and sorted rows. A state that has places but no
 * published browse view yet falls back to the file order.
 */
private fun browseRows(state: FavouriteListsState): List<SavedPoint> =
    if (state.browse.rows.isEmpty() && state.browse.query.isBlank()) {
        state.points.map { SavedPoint(it.id, it.name, it.list, it.special, null, it.position) }
    } else {
        state.browse.rows.map { SavedPoint(it.id, it.name, it.list, it.special, it.description, it.position) }
    }

private fun subtitle(
    placeCount: Int,
    groupCount: Int,
    strings: Strings,
): String {
    val places = placesPhrase(placeCount, strings)
    if (groupCount == 0) return places
    return strings.resolve(SAVED_SUBTITLE_KEY, listOf(places, groupsPhrase(groupCount, strings)))
}

private fun savedGroup(
    key: SavedGroupKey,
    points: List<SavedPoint>,
    strings: Strings,
    commands: SavedCommands,
    hiddenGroups: Set<String>,
): SavedGroupRow {
    val name = key.name
    val shown = name !in hiddenGroups
    val visibilityKey = if (shown) SAVED_HIDE_GROUP_KEY else SAVED_SHOW_GROUP_KEY
    return groupRow(
        name = name,
        list = name,
        places = points.filter { it.list == name }.map { placeRow(it, strings) },
        shown = shown,
        strings = strings,
    ).copy(
        color = key.color?.let(::favouriteColour),
        canToggleVisibility = commands.visibility,
        canRecolour = key.canRecolour,
        visibilityLabel = strings.resolve(visibilityKey, listOf(name)),
        recolourLabel = if (key.canRecolour) savedRecolourTitle(name, strings) else null,
    )
}

/** The places with no group. The map always draws them, so the row has no eye. */
private fun ungroupedRow(
    points: List<SavedPoint>,
    strings: Strings,
): SavedGroupRow =
    groupRow(
        name = strings.resolve(NO_LIST_KEY),
        list = null,
        places = points.filter { it.list == null }.map { placeRow(it, strings) },
        shown = true,
        strings = strings,
    )

/** A group without controls: the summary, the preview rows and the "more" line. */
private fun groupRow(
    name: String,
    list: String?,
    places: List<SavedPlaceRow>,
    shown: Boolean,
    strings: Strings,
): SavedGroupRow {
    val more = (places.size - SAVED_PLACE_PREVIEW).coerceAtLeast(0)
    val summaryKey = if (shown) SAVED_GROUP_ON_MAP_KEY else SAVED_GROUP_OFF_MAP_KEY
    return SavedGroupRow(
        name = name,
        list = list,
        color = null,
        shown = shown,
        canToggleVisibility = false,
        canRecolour = false,
        visibilityLabel = null,
        recolourLabel = null,
        summary = strings.resolve(summaryKey, listOf(placesPhrase(places.size, strings))),
        places = places.take(SAVED_PLACE_PREVIEW),
        remaining = places.drop(SAVED_PLACE_PREVIEW),
        more = more,
        moreLabel = if (more == 0) null else strings.resolve(SAVED_MORE_KEY, listOf(more.toString(), name)),
    )
}

private fun placeRow(
    point: SavedPoint,
    strings: Strings,
): SavedPlaceRow =
    SavedPlaceRow(
        id = point.id,
        name = point.name,
        detail = point.description?.takeIf { it.isNotBlank() },
        special = specialLabel(point.special, strings),
        position = point.position,
    )

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
 * The labels of the Saved panels: the + panel's new group form and one place's
 * actions. Undo, close and the sort names come from the lists resolvers.
 */
data class SavedSheetText(
    val newGroup: String,
    val name: String,
    val create: String,
    val cancel: String,
    val sortTitle: String,
    val showOnMap: String,
    val setHome: String,
    val setWork: String,
)

fun savedSheetText(strings: Strings): SavedSheetText =
    SavedSheetText(
        newGroup = strings.resolve(SAVED_NEW_GROUP_KEY),
        name = strings.resolve(SAVED_GROUP_NAME_KEY),
        create = strings.resolve(SAVED_CREATE_KEY),
        cancel = strings.resolve(SAVED_CANCEL_KEY),
        sortTitle = strings.resolve(SAVED_SORT_KEY),
        showOnMap = strings.resolve(SAVED_SHOW_ON_MAP_KEY),
        setHome = strings.resolve(SAVED_SET_HOME_KEY),
        setWork = strings.resolve(SAVED_SET_WORK_KEY),
    )

/** The title of the colour row of one group. */
fun savedRecolourTitle(
    group: String,
    strings: Strings,
): String = strings.resolve(SAVED_RECOLOUR_KEY, listOf(group))

/** One list a selected place can move to, with its resolved action label. */
data class SavedMoveTarget(
    val list: String,
    val label: String,
)

/**
 * The declared lists a place can move to: every list but its own. A place
 * cannot move into a group name that has no definition.
 */
fun savedMoveTargets(
    state: FavouriteListsState,
    pointId: String,
    strings: Strings,
): List<SavedMoveTarget> {
    val current = state.points.firstOrNull { it.id == pointId }?.list
    return state.lists
        .map { it.name }
        .filter { it != current }
        .map { list -> SavedMoveTarget(list, strings.resolve(SAVED_MOVE_TO_KEY, listOf(list))) }
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
internal const val SAVED_NEW_GROUP_KEY = "saved_screen_new_group"
internal const val SAVED_GROUP_NAME_KEY = "saved_screen_group_name"
internal const val SAVED_CREATE_KEY = "saved_screen_create"
internal const val SAVED_CANCEL_KEY = "saved_screen_cancel"
internal const val SAVED_RECOLOUR_KEY = "saved_screen_recolour"
internal const val SAVED_SHOW_ON_MAP_KEY = "saved_screen_show_on_map"
internal const val SAVED_SET_HOME_KEY = "saved_screen_set_home"
internal const val SAVED_SET_WORK_KEY = "saved_screen_set_work"
internal const val SAVED_MOVE_TO_KEY = "saved_screen_move_to"

/** The canon shows a few place rows and folds the rest into a "more" line. */
private const val SAVED_PLACE_PREVIEW = 2
