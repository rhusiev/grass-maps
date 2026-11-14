package nl.r1a.grassmap.plugin.api.model

/**
 * One point of a track, route or waypoint. [elevationMeters] and [timeMillis]
 * are absent in the file; [timeMillis] is UTC epoch milliseconds.
 */
data class TrackPoint(
    val position: GeoPoint,
    val elevationMeters: Double? = null,
    val timeMillis: Long? = null,
)

/** A contiguous run of track points. A gap between segments is not crossed. */
data class TrackSegment(
    val points: List<TrackPoint>,
)

/**
 * An ordered track: one or more segments read from one file. [style] is the
 * library's presentation, absent on an import; an export writes it as a
 * namespaced GPX extension and the sidecar stays the store's source of truth.
 */
data class Track(
    val name: String? = null,
    val segments: List<TrackSegment>,
    val style: TrackStyle? = null,
)

/** A route read from a file. A route is not a travelled track yet (T-0411). */
data class TrackRoute(
    val name: String? = null,
    val points: List<TrackPoint>,
)

/**
 * A named standalone point read from a file. [description] is the free text a
 * GPX `<desc>` carries, kept beside the name so a waypoint survives an export
 * with what its author wrote (T-0411). The symbol and type elements have no
 * field: this build has no icon vocabulary, so they are dropped on import and
 * an export writes no `<sym>` or `<type>`.
 */
data class TrackWaypoint(
    val name: String? = null,
    val position: GeoPoint,
    val elevationMeters: Double? = null,
    val timeMillis: Long? = null,
    val description: String? = null,
)

/** Everything a file format can produce in one import. */
data class ImportedEntities(
    val tracks: List<Track> = emptyList(),
    val routes: List<TrackRoute> = emptyList(),
    val waypoints: List<TrackWaypoint> = emptyList(),
)

/** The entity families a file format can read or write. */
enum class FileEntityKind {
    Track,
    Route,
    Waypoint,
}

/**
 * What a stored record is (T-0411). [Track] is a line that was travelled or
 * imported from a file; [Route] is a route a user planned and saved, which the
 * library also keeps, and which is a `<rte>` in the GPX rather than a `<trk>`.
 *
 * The value is stored on the record, never derived from its name, so a row can
 * say which one it is without guessing.
 */
enum class TrackKind(
    val wireName: String,
) {
    Track("track"),
    Route("route"),
    ;

    companion object {
        /** The kind whose stored name is [name], or null for a value this build does not know. */
        fun ofWireName(name: String?): TrackKind? = entries.firstOrNull { kind -> kind.wireName == name }
    }
}

/**
 * Why a part of a document did not become an entity. The code is stable; the
 * [ImportWarning.detail] is a structural locator such as `trk[0]/trkseg[1]`,
 * never user-facing prose.
 */
enum class ImportWarningCode {
    MissingCoordinates,
    OutOfRangeCoordinates,
    InvalidElevation,
    InvalidTime,
    EmptySegment,
    EmptyTrack,
    EmptyRoute,
}

/** One skipped or repaired part of a document. */
data class ImportWarning(
    val code: ImportWarningCode,
    val detail: String? = null,
)

/** The entities read from one document and the parts that were skipped. */
data class ImportResult(
    val entities: ImportedEntities,
    val warnings: List<ImportWarning> = emptyList(),
)

/**
 * The styling a stored track carries in its sidecar (T-0401). [colour] is a
 * `#RRGGBB` string so the sidecar stays readable and has no platform type.
 */
data class TrackStyle(
    val colour: String = DEFAULT_COLOUR,
    val widthDp: Float = DEFAULT_WIDTH_DP,
) {
    /** True when [colour] is a plain `#RRGGBB` value and the width is drawable. */
    fun isValid(): Boolean = COLOUR_PATTERN.matches(colour) && widthDp > 0f && widthDp.isFinite()

    companion object {
        const val DEFAULT_COLOUR: String = "#2A78D6"
        const val DEFAULT_WIDTH_DP: Float = 4f

        private val COLOUR_PATTERN = Regex("^#[0-9A-Fa-f]{6}$")
    }
}

/**
 * One stored track as the map draws it: styled segments, no analysis. The
 * elevation profile stays on the store's sidecar and never reaches the layer.
 * A segment with fewer than two points cannot form a line.
 */
data class MapTrack(
    val id: String,
    val name: String? = null,
    val style: TrackStyle = TrackStyle(),
    val segments: List<List<GeoPoint>>,
)
