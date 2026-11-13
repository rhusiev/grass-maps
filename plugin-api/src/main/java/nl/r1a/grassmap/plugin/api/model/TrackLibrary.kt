package nl.r1a.grassmap.plugin.api.model

import kotlinx.serialization.Serializable

/**
 * One track folder as the library screen draws its tile (T-0414).
 *
 * [id] is stable across renames, so moving a track to a renamed folder keeps it
 * there. [trackCount] and [distanceMeters] are derived from the tracks that
 * name the folder and are never stored on the folder itself.
 */
@Serializable
data class TrackFolderSummary(
    val id: String,
    val name: String,
    val trackCount: Int = 0,
    val distanceMeters: Double = 0.0,
)

/**
 * The four fixed track orders the screen offers (T-0414): Newest first, Nearest
 * first, Name A-Z and Longest first, in that order.
 *
 * Every order is deterministic and locale-independent, so one library draws the
 * same rows on every device. [NEWEST] orders by the stored save time, newest
 * first. [NEAREST] orders by the ground distance from the map centre (or the
 * current fix when one is supplied) to the first point of the track, closest
 * first; a track without a start point goes last and, without an origin, the
 * order falls back to [NAME]. [NAME] compares lowercased names with the JVM's
 * ordinary string order and puts an unnamed track after a named one. [DISTANCE]
 * is Longest first. Ties keep the library's file order, so a sort is stable.
 */
@Serializable
enum class TrackSort(
    val wireName: String,
) {
    NEWEST("newest"),
    NEAREST("nearest"),
    NAME("name"),
    DISTANCE("distance"),
    ;

    companion object {
        /** Parses the command wire name; an unknown value returns null. */
        fun fromWire(value: String): TrackSort? = entries.firstOrNull { sort -> sort.wireName == value.trim().lowercase() }
    }
}

/**
 * What the last library action was, so the screen can name the undo it offers.
 * [NONE] means there is nothing to undo.
 */
@Serializable
enum class TrackLibraryAction {
    NONE,
    FOLDER_CREATED,
    FOLDER_RENAMED,
    TRACK_EDITED,
    TRACK_MOVED,
    TRACK_DELETED,
}

/**
 * One stored track as the library publishes it (T-0414).
 *
 * [description] is the stored value and may carry simple HTML, so a renderer
 * must sanitise it before the screen draws it. [listId] is the folder the track
 * belongs to, or null when it is unfiled. [startLatitude] and [startLongitude]
 * are the first point of the track, used by the Nearest order, or null when the
 * document carries no geometry.
 */
@Serializable
data class TrackLibraryEntry(
    val id: String,
    val name: String? = null,
    val listId: String? = null,
    val distanceMeters: Double = 0.0,
    val ascentMeters: Double = 0.0,
    val durationMillis: Long? = null,
    val savedAtMillis: Long = 0L,
    val description: String? = null,
    val colour: String? = null,
    val startLatitude: Double? = null,
    val startLongitude: Double? = null,
)

/**
 * One rendered list row (T-0414). [description] is the sanitised native styled
 * text, never the stored markup. [listName] is the resolved folder name, so a
 * row that is shown outside its folder still says where the track lives.
 */
@Serializable
data class TrackLibraryRow(
    val id: String,
    val name: String? = null,
    val listId: String? = null,
    val listName: String? = null,
    val distanceMeters: Double = 0.0,
    val ascentMeters: Double = 0.0,
    val durationMillis: Long? = null,
    val savedAtMillis: Long = 0L,
    val description: RichText? = null,
    val colour: String? = null,
    val startLatitude: Double? = null,
    val startLongitude: Double? = null,
)

/**
 * The browsed view of the library published inside [TrackLibraryState].
 *
 * [query] is matched against the track name, case- and accent-insensitively; a
 * blank query keeps every track. [selectedListId] filters the rows to one
 * folder, or null for every folder. [rows] is the result of applying the
 * folder filter, the query and the chosen order. [originLatitude] and
 * [originLongitude] are the position the Nearest order measured from, or null
 * when the host sent none.
 */
@Serializable
data class TrackBrowseState(
    val query: String = "",
    val sort: TrackSort = TrackSort.NEWEST,
    val selectedListId: String? = null,
    val originLatitude: Double? = null,
    val originLongitude: Double? = null,
    val rows: List<TrackLibraryRow> = emptyList(),
)

/**
 * The library screen state published by `plugins/track-library`.
 *
 * [folders] is the folder order the user created, [tracks] the library's file
 * order. [browse] is the derived view the screen draws and is never persisted.
 * [undoAvailable] is owned by the plugin's scoped receipt stack; [lastAction]
 * names what that one undo would reverse.
 */
@Serializable
data class TrackLibraryState(
    val folders: List<TrackFolderSummary> = emptyList(),
    val tracks: List<TrackLibraryEntry> = emptyList(),
    val undoAvailable: Boolean = false,
    val lastAction: TrackLibraryAction = TrackLibraryAction.NONE,
    val browse: TrackBrowseState = TrackBrowseState(),
)
