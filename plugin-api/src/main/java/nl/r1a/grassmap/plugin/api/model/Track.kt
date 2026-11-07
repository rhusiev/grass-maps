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

/** An ordered track: one or more segments read from one file. */
data class Track(
    val name: String? = null,
    val segments: List<TrackSegment>,
)

/** A route read from a file. A route is not a travelled track yet (T-0411). */
data class TrackRoute(
    val name: String? = null,
    val points: List<TrackPoint>,
)

/** A named standalone point read from a file. */
data class TrackWaypoint(
    val name: String? = null,
    val position: GeoPoint,
    val elevationMeters: Double? = null,
    val timeMillis: Long? = null,
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
