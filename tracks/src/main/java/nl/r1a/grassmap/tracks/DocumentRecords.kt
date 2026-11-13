package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.model.ImportedEntities
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackKind
import nl.r1a.grassmap.plugin.api.model.TrackRoute
import nl.r1a.grassmap.plugin.api.model.TrackSegment

/**
 * One record a document yields, with the geometry a library record stores.
 *
 * A document has two families of record: the `<trk>` elements and the `<rte>`
 * elements (T-0411). [trackIndex] is the record's position in the document's
 * whole record order - every track first, then every route - which is the index
 * the record id is derived from, so the importer and the loader cannot disagree
 * about which record an id names.
 */
internal data class DocumentRecord(
    val kind: TrackKind,
    val track: Track,
    val trackIndex: Int,
)

/**
 * The records of [this] document in id order. A route becomes a track with one
 * segment, because the store keeps one geometry shape; [DocumentRecord.kind]
 * keeps the distinction the row and an export need.
 */
internal fun ImportedEntities.records(): List<DocumentRecord> {
    val tracks = tracks.map { track -> DocumentRecord(TrackKind.Track, track, trackIndex = 0) }
    val routes = routes.map(::routeRecord)
    return (tracks + routes).mapIndexed { index, record -> record.copy(trackIndex = index) }
}

private fun routeRecord(route: TrackRoute): DocumentRecord =
    DocumentRecord(
        kind = TrackKind.Route,
        track = route.asTrack(),
        trackIndex = 0,
    )

/**
 * A route drawn as one segment. A route has no segment boundaries: the planner
 * produces one ordered line, and its stops are a separate record.
 */
internal fun TrackRoute.asTrack(): Track =
    Track(
        name = name,
        segments = listOf(TrackSegment(points)),
    )
