package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.TrackLibraryAction
import nl.r1a.grassmap.plugin.api.model.TrackLibraryRow
import nl.r1a.grassmap.plugin.api.model.TrackLibraryState
import nl.r1a.grassmap.plugin.api.model.TrackSort
import nl.r1a.grassmap.plugin.api.service.Strings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One folder tile: the id the filter uses, its name and its derived summary. */
data class TrackFolderRow(
    val id: String?,
    val name: String,
    val summary: String,
    val selected: Boolean,
)

/** One list row with every value already formatted and safe to draw. */
data class TrackLibraryRowContent(
    val id: String,
    val name: String,
    val meta: String,
    val listId: String?,
    val listName: String?,
    val description: String?,
    val colour: Long?,
)

/** One sort choice the screen offers, with its resolved label. */
data class TrackSortRow(
    val sort: TrackSort,
    val label: String,
    val selected: Boolean,
)

/** The whole library screen's text, ready to draw. */
data class TrackLibraryContent(
    val title: String,
    val subtitle: String,
    val close: String,
    val undo: String,
    val undoMessage: String?,
    val allTracks: String,
    val newFolder: String,
    val renameFolder: String,
    val sort: String,
    val sorts: List<TrackSortRow>,
    val search: String,
    val searchHint: String,
    val listTitle: String,
    val listSummary: String,
    val empty: String?,
    val noMatches: String?,
    val folders: List<TrackFolderRow>,
    val rows: List<TrackLibraryRowContent>,
)

/** One action in the track action sheet. */
enum class TrackActionKind {
    EDIT,
    MOVE,
    SHARE,
    DELETE,
}

data class TrackActionRow(
    val kind: TrackActionKind,
    val label: String,
)

/** The action sheet of one track, with every label resolved. */
data class TrackActionSheetContent(
    val title: String,
    val subtitle: String,
    val actions: List<TrackActionRow>,
    val cancel: String,
)

/** One folder choice in the edit sheet. [id] is null for "no folder". */
data class TrackFolderChoice(
    val id: String?,
    val name: String,
    val selected: Boolean,
)

/** The edit sheet of one track, with every label resolved. */
data class TrackEditContent(
    val title: String,
    val nameLabel: String,
    val name: String,
    val folderLabel: String,
    val folders: List<TrackFolderChoice>,
    val newFolder: String,
    val descriptionLabel: String,
    val description: String,
    val descriptionNote: String,
    val cancel: String,
    val save: String,
)

/** The new-folder and rename-folder sheet, with every label resolved. */
data class TrackFolderSheetContent(
    val title: String,
    val nameLabel: String,
    val name: String,
    val cancel: String,
    val save: String,
)

/**
 * The library screen's text (T-0414), resolved once for the active language.
 *
 * The plugin publishes the filtered, sorted and rendered rows; this resolver
 * only formats their numbers and dates and turns labels into the active
 * language. The description is already sanitised plain text, so the screen
 * never draws markup.
 */
fun trackLibraryContent(
    state: TrackLibraryState,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
): TrackLibraryContent {
    val browse = state.browse
    val selected = state.folders.firstOrNull { folder -> folder.id == browse.selectedListId }
    val allDistance = state.tracks.sumOf { track -> track.distanceMeters }
    val listTitle = selected?.name ?: strings.resolve(TRACK_LIBRARY_ALL_TRACKS_KEY)
    val listSummary =
        strings.resolve(
            TRACK_LIBRARY_LIST_SUMMARY_KEY,
            listOf(
                state.tracks.count { track -> selected == null || track.listId == selected.id }.toString(),
                distanceText(allDistanceFor(state, selected?.id), strings, locale),
                ascentText(allAscentFor(state, selected?.id), strings),
            ),
        )
    return TrackLibraryContent(
        title = strings.resolve(TRACK_LIBRARY_TITLE_KEY),
        subtitle =
            strings.resolve(
                TRACK_LIBRARY_SUBTITLE_KEY,
                listOf(state.tracks.size.toString(), state.folders.size.toString()),
            ),
        close = strings.resolve(TRACK_LIBRARY_CLOSE_KEY),
        undo = strings.resolve(TRACK_LIBRARY_UNDO_KEY),
        undoMessage = if (state.undoAvailable) undoMessage(state.lastAction, strings) else null,
        allTracks = strings.resolve(TRACK_LIBRARY_ALL_TRACKS_KEY),
        newFolder = strings.resolve(TRACK_LIBRARY_NEW_FOLDER_KEY),
        renameFolder = strings.resolve(TRACK_LIBRARY_RENAME_FOLDER_KEY),
        sort = strings.resolve(TRACK_LIBRARY_SORT_KEY),
        sorts =
            TrackSort.entries.map { sort ->
                TrackSortRow(
                    sort = sort,
                    label = strings.resolve(trackSortKey(sort)),
                    selected = sort == browse.sort,
                )
            },
        search = strings.resolve(TRACK_LIBRARY_SEARCH_KEY),
        searchHint = strings.resolve(TRACK_LIBRARY_SEARCH_HINT_KEY, listOf(state.tracks.size.toString())),
        listTitle = listTitle,
        listSummary = listSummary,
        empty = if (state.tracks.isEmpty()) strings.resolve(TRACK_LIBRARY_EMPTY_KEY) else null,
        noMatches = if (browse.rows.isEmpty() && state.tracks.isNotEmpty()) strings.resolve(TRACK_LIBRARY_NO_MATCHES_KEY) else null,
        folders =
            listOf(
                TrackFolderRow(
                    id = null,
                    name = strings.resolve(TRACK_LIBRARY_ALL_TRACKS_KEY),
                    summary =
                        strings.resolve(
                            TRACK_LIBRARY_FOLDER_SUMMARY_KEY,
                            listOf(state.tracks.size.toString(), distanceText(allDistance, strings, locale)),
                        ),
                    selected = browse.selectedListId == null,
                ),
            ) +
                state.folders.map { folder ->
                    TrackFolderRow(
                        id = folder.id,
                        name = folder.name,
                        summary =
                            strings.resolve(
                                TRACK_LIBRARY_FOLDER_SUMMARY_KEY,
                                listOf(folder.trackCount.toString(), distanceText(folder.distanceMeters, strings, locale)),
                            ),
                        selected = folder.id == browse.selectedListId,
                    )
                },
        rows = browse.rows.map { row -> trackRowContent(row, strings, locale) },
    )
}

/**
 * The row a sheet may act on. A selected id that the current browse view no
 * longer contains has no row, so the caller closes the sheet instead of
 * binding it to a different track (T-0414).
 */
internal fun selectedLibraryRow(
    rows: List<TrackLibraryRowContent>,
    selectedId: String?,
): TrackLibraryRowContent? = rows.firstOrNull { row -> row.id == selectedId }

/** The action sheet of one row; [kind] decides what the shell does with a tap. */
fun trackActionSheetContent(
    row: TrackLibraryRowContent,
    strings: Strings,
): TrackActionSheetContent =
    TrackActionSheetContent(
        title = row.name,
        subtitle = row.listName ?: strings.resolve(TRACK_LIBRARY_NO_FOLDER_KEY),
        actions =
            listOf(
                TrackActionRow(TrackActionKind.EDIT, strings.resolve(TRACK_LIBRARY_ACTION_EDIT_KEY)),
                TrackActionRow(TrackActionKind.MOVE, strings.resolve(TRACK_LIBRARY_ACTION_MOVE_KEY)),
                TrackActionRow(TrackActionKind.SHARE, strings.resolve(TRACK_LIBRARY_ACTION_SHARE_KEY)),
                TrackActionRow(TrackActionKind.DELETE, strings.resolve(TRACK_LIBRARY_ACTION_DELETE_KEY)),
            ),
        cancel = strings.resolve(TRACK_LIBRARY_CANCEL_KEY),
    )

/** The edit sheet of one row, with its current folder selected. */
fun trackEditContent(
    row: TrackLibraryRowContent,
    state: TrackLibraryState,
    strings: Strings,
): TrackEditContent =
    TrackEditContent(
        title = strings.resolve(TRACK_LIBRARY_EDIT_TITLE_KEY),
        nameLabel = strings.resolve(TRACK_LIBRARY_NAME_KEY),
        name = row.name,
        folderLabel = strings.resolve(TRACK_LIBRARY_FOLDER_KEY),
        folders =
            listOf(
                TrackFolderChoice(
                    id = null,
                    name = strings.resolve(TRACK_LIBRARY_NO_FOLDER_KEY),
                    selected = row.listId == null,
                ),
            ) +
                state.folders.map { folder ->
                    TrackFolderChoice(
                        id = folder.id,
                        name = folder.name,
                        selected = folder.id == row.listId,
                    )
                },
        newFolder = strings.resolve(TRACK_LIBRARY_NEW_FOLDER_KEY),
        descriptionLabel = strings.resolve(TRACK_LIBRARY_DESCRIPTION_KEY),
        description = state.tracks.firstOrNull { track -> track.id == row.id }?.description ?: row.description.orEmpty(),
        descriptionNote = strings.resolve(TRACK_LIBRARY_DESCRIPTION_NOTE_KEY),
        cancel = strings.resolve(TRACK_LIBRARY_CANCEL_KEY),
        save = strings.resolve(TRACK_LIBRARY_SAVE_KEY),
    )

/** The new-folder or rename-folder sheet. */
fun trackFolderSheetContent(
    title: String,
    currentName: String,
    strings: Strings,
): TrackFolderSheetContent =
    TrackFolderSheetContent(
        title = title,
        nameLabel = strings.resolve(TRACK_LIBRARY_FOLDER_NAME_KEY),
        name = currentName,
        cancel = strings.resolve(TRACK_LIBRARY_CANCEL_KEY),
        save = strings.resolve(TRACK_LIBRARY_SAVE_KEY),
    )

internal fun trackSortKey(sort: TrackSort): String =
    when (sort) {
        TrackSort.NEWEST -> TRACK_LIBRARY_SORT_NEWEST_KEY
        TrackSort.NAME -> TRACK_LIBRARY_SORT_NAME_KEY
        TrackSort.DISTANCE -> TRACK_LIBRARY_SORT_DISTANCE_KEY
        TrackSort.DURATION -> TRACK_LIBRARY_SORT_DURATION_KEY
    }

private fun trackRowContent(
    row: TrackLibraryRow,
    strings: Strings,
    locale: Locale,
): TrackLibraryRowContent {
    val parts = mutableListOf<String>()
    if (row.distanceMeters > 0.0) {
        parts += distanceText(row.distanceMeters, strings, locale)
    }
    if (row.ascentMeters > 0.0) {
        parts += ascentText(row.ascentMeters, strings)
    }
    row.durationMillis?.let { millis -> parts += durationText(millis / MILLIS_PER_SECOND, strings) }
    if (row.savedAtMillis > 0L) {
        parts += dateText(row.savedAtMillis, locale)
    }
    return TrackLibraryRowContent(
        id = row.id,
        name = row.name?.takeIf { it.isNotBlank() } ?: strings.resolve(TRACK_LIBRARY_UNTITLED_KEY),
        meta = parts.joinToString(META_SEPARATOR),
        listId = row.listId,
        listName = row.listName,
        description = row.description,
        colour = row.colour?.let(::favouriteColour),
    )
}

private fun undoMessage(
    action: TrackLibraryAction,
    strings: Strings,
): String? =
    when (action) {
        TrackLibraryAction.NONE -> null
        TrackLibraryAction.FOLDER_CREATED -> strings.resolve(TRACK_LIBRARY_UNDO_CREATED_FOLDER_KEY)
        TrackLibraryAction.FOLDER_RENAMED -> strings.resolve(TRACK_LIBRARY_UNDO_RENAMED_FOLDER_KEY)
        TrackLibraryAction.TRACK_EDITED -> strings.resolve(TRACK_LIBRARY_UNDO_EDITED_TRACK_KEY)
        TrackLibraryAction.TRACK_MOVED -> strings.resolve(TRACK_LIBRARY_UNDO_MOVED_TRACK_KEY)
        TrackLibraryAction.TRACK_DELETED -> strings.resolve(TRACK_LIBRARY_UNDO_DELETED_TRACK_KEY)
    }

private fun allDistanceFor(
    state: TrackLibraryState,
    listId: String?,
): Double = state.tracks.filter { track -> listId == null || track.listId == listId }.sumOf { track -> track.distanceMeters }

private fun allAscentFor(
    state: TrackLibraryState,
    listId: String?,
): Double = state.tracks.filter { track -> listId == null || track.listId == listId }.sumOf { track -> track.ascentMeters }

internal fun ascentText(
    meters: Double,
    strings: Strings,
): String = ASCENT_PREFIX + strings.resolve(METRES_KEY, listOf(meters.toLong().toString()))

private fun dateText(
    millis: Long,
    locale: Locale,
): String = DateTimeFormatter.ofPattern(DATE_PATTERN, locale).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

internal const val TRACK_LIBRARY_TITLE_KEY = "track_library_title"
internal const val TRACK_LIBRARY_CLOSE_KEY = "track_library_close"
internal const val TRACK_LIBRARY_SUBTITLE_KEY = "track_library_subtitle"
internal const val TRACK_LIBRARY_ALL_TRACKS_KEY = "track_library_all_tracks"
internal const val TRACK_LIBRARY_NEW_FOLDER_KEY = "track_library_new_folder"
internal const val TRACK_LIBRARY_RENAME_FOLDER_KEY = "track_library_rename_folder"
internal const val TRACK_LIBRARY_FOLDER_SUMMARY_KEY = "track_library_folder_summary"
internal const val TRACK_LIBRARY_LIST_SUMMARY_KEY = "track_library_list_summary"
internal const val TRACK_LIBRARY_SEARCH_KEY = "track_library_search"
internal const val TRACK_LIBRARY_SEARCH_HINT_KEY = "track_library_search_hint"
internal const val TRACK_LIBRARY_SORT_KEY = "track_library_sort"
internal const val TRACK_LIBRARY_SORT_NEWEST_KEY = "track_library_sort_newest"
internal const val TRACK_LIBRARY_SORT_NAME_KEY = "track_library_sort_name"
internal const val TRACK_LIBRARY_SORT_DISTANCE_KEY = "track_library_sort_distance"
internal const val TRACK_LIBRARY_SORT_DURATION_KEY = "track_library_sort_duration"
internal const val TRACK_LIBRARY_EMPTY_KEY = "track_library_empty"
internal const val TRACK_LIBRARY_NO_MATCHES_KEY = "track_library_no_matches"
internal const val TRACK_LIBRARY_UNTITLED_KEY = "track_analysis_untitled"
internal const val TRACK_LIBRARY_UNDO_KEY = "track_library_undo"
internal const val TRACK_LIBRARY_UNDO_CREATED_FOLDER_KEY = "track_library_undo_created_folder"
internal const val TRACK_LIBRARY_UNDO_RENAMED_FOLDER_KEY = "track_library_undo_renamed_folder"
internal const val TRACK_LIBRARY_UNDO_EDITED_TRACK_KEY = "track_library_undo_edited_track"
internal const val TRACK_LIBRARY_UNDO_MOVED_TRACK_KEY = "track_library_undo_moved_track"
internal const val TRACK_LIBRARY_UNDO_DELETED_TRACK_KEY = "track_library_undo_deleted_track"
internal const val TRACK_LIBRARY_ACTION_EDIT_KEY = "track_library_action_edit"
internal const val TRACK_LIBRARY_ACTION_MOVE_KEY = "track_library_action_move"
internal const val TRACK_LIBRARY_ACTION_SHARE_KEY = "track_library_action_share"
internal const val TRACK_LIBRARY_ACTION_DELETE_KEY = "track_library_action_delete"
internal const val TRACK_LIBRARY_NO_FOLDER_KEY = "track_library_no_folder"
internal const val TRACK_LIBRARY_EDIT_TITLE_KEY = "track_library_edit_title"
internal const val TRACK_LIBRARY_NAME_KEY = "track_library_name"
internal const val TRACK_LIBRARY_FOLDER_KEY = "track_library_folder"
internal const val TRACK_LIBRARY_DESCRIPTION_KEY = "track_library_description"
internal const val TRACK_LIBRARY_DESCRIPTION_NOTE_KEY = "track_library_description_note"
internal const val TRACK_LIBRARY_CANCEL_KEY = "track_library_cancel"
internal const val TRACK_LIBRARY_SAVE_KEY = "track_library_save"
internal const val TRACK_LIBRARY_FOLDER_NAME_KEY = "track_library_folder_name"
internal const val TRACK_LIBRARY_CREATE_FOLDER_TITLE_KEY = "track_library_create_folder_title"
internal const val TRACK_LIBRARY_RENAME_FOLDER_TITLE_KEY = "track_library_rename_folder_title"

private const val METRES_KEY = "track_analysis_metres"
private const val META_SEPARATOR = " · "
private const val ASCENT_PREFIX = "↑"
private const val DATE_PATTERN = "d MMM"
private const val MILLIS_PER_SECOND = 1_000.0
