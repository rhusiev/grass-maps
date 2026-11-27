package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.FavouriteListsState
import nl.r1a.grassmaps.plugin.api.model.FavouritePointSummary
import nl.r1a.grassmaps.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.TrackLibraryEntry
import nl.r1a.grassmaps.plugin.api.service.Strings

/** The picture a saved tile draws in its round badge. */
enum class SavedTileGlyph { Home, Place, Track }

/** What a tap on a saved tile opens. [center] is where the map moves, or null to stay. */
sealed interface SavedTileTarget {
    val center: GeoPoint?

    /** A saved place: the map moves to [position]. */
    data class Place(
        val position: GeoPoint,
    ) : SavedTileTarget {
        override val center: GeoPoint get() = position
    }

    /** A saved track: the map opens the track's card and moves to its [start]. */
    data class Track(
        val id: String,
        val start: GeoPoint?,
    ) : SavedTileTarget {
        override val center: GeoPoint? get() = start
    }
}

/**
 * One tile of the half sheet's Saved grid (round 9 `k-half`): a badge in the
 * item's own color, the name, and the distance from the fix or the track's
 * length. [colour] is ARGB, or null for the neutral badge.
 */
data class SavedTile(
    val key: String,
    val name: String,
    val detail: String?,
    val glyph: SavedTileGlyph,
    val colour: Long?,
    val target: SavedTileTarget,
)

/** The Saved section: at most [SAVED_TILE_LIMIT] tiles, and the count the "All" action names. */
data class SavedSheetContent(
    val tiles: List<SavedTile>,
    val allCount: Int,
)

/** The data the Saved tiles are built from. [origin] is the current fix, or null without one. */
data class SavedSheetInput(
    val places: FavouriteListsState,
    val tracks: List<TrackLibraryEntry>,
    val origin: GeoPoint?,
    val hiddenGroups: Set<String>,
)

/** How the tiles measure and word distances. The app passes its geodesic distance and its units. */
data class SavedSheetFormat(
    val strings: Strings,
    val distanceMeters: (GeoPoint, GeoPoint) -> Double,
    val formatDistance: (Double) -> String,
)

/**
 * The Saved tiles in their order: Home, then Work; then the newest track, so a
 * track shows beside the places as in `k-half`; then the other places, nearest
 * first with a fix and in list order without one; then the older tracks.
 * Places in a group hidden on the map and hidden tracks are left out, because
 * a tap shows the item on the map. [SavedSheetContent.allCount] counts every
 * saved place and track, which is what the full Saved screen lists.
 */
fun savedSheetContent(
    input: SavedSheetInput,
    format: SavedSheetFormat,
): SavedSheetContent {
    val places = input.places.points.filter { point -> point.position != null && point.list !in input.hiddenGroups }
    val special = FavouriteSpecial.entries.mapNotNull { kind -> places.firstOrNull { it.special == kind } }
    val others = orderedPlaces(places - special.toSet(), input.origin, format)
    val tracks = input.tracks.filterNot { it.hidden }.sortedByDescending { it.savedAtMillis }
    val placeTile = { point: FavouritePointSummary -> point.position?.let { placeTile(point, it, input, format) } }
    val tiles =
        special.mapNotNull(placeTile) +
            tracks.take(1).map { trackTile(it, format) } +
            others.mapNotNull(placeTile) +
            tracks.drop(1).map { trackTile(it, format) }
    return SavedSheetContent(
        tiles = tiles.take(SAVED_TILE_LIMIT),
        allCount = input.places.points.size + input.tracks.size,
    )
}

private fun orderedPlaces(
    places: List<FavouritePointSummary>,
    origin: GeoPoint?,
    format: SavedSheetFormat,
): List<FavouritePointSummary> {
    origin ?: return places
    return places.sortedBy { point -> point.position?.let { format.distanceMeters(origin, it) } }
}

private fun placeTile(
    point: FavouritePointSummary,
    position: GeoPoint,
    input: SavedSheetInput,
    format: SavedSheetFormat,
): SavedTile {
    val specialName = specialLabel(point.special, format.strings)
    // Without a fix, the line under the name says what the name does not:
    // Home and Work show the place's own name, a grouped place its group.
    val detail =
        input.origin?.let { origin -> format.formatDistance(format.distanceMeters(origin, position)) }
            ?: if (specialName != null) point.name else point.list
    val colour =
        input.places.lists
            .firstOrNull { it.name == point.list }
            ?.let { favouriteColour(it.color) }
    return SavedTile(
        key = "place:${point.id}",
        name = specialName ?: point.name,
        detail = detail,
        glyph = if (point.special == FavouriteSpecial.HOME) SavedTileGlyph.Home else SavedTileGlyph.Place,
        colour = colour,
        target = SavedTileTarget.Place(position),
    )
}

private fun trackTile(
    track: TrackLibraryEntry,
    format: SavedSheetFormat,
): SavedTile {
    val start =
        track.startLatitude?.let { latitude ->
            track.startLongitude?.let { longitude -> GeoPoint(latitude, longitude) }
        }
    return SavedTile(
        key = "track:${track.id}",
        name = track.name?.takeIf { it.isNotBlank() } ?: format.strings.resolve(TRACK_LIBRARY_UNTITLED_KEY),
        detail = format.strings.resolve(TRACK_LENGTH_KEY, listOf(format.formatDistance(track.distanceMeters))),
        glyph = SavedTileGlyph.Track,
        colour = track.colour?.let(::favouriteColour),
        target = SavedTileTarget.Track(track.id, start),
    )
}

/** Two rows of three, as `k-half` draws them. */
const val SAVED_TILE_LIMIT = 6

internal const val TRACK_LENGTH_KEY = "map_sheet_track_length"
