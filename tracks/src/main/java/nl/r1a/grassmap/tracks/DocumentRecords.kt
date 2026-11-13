package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.model.ImportedEntities
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackKind
import nl.r1a.grassmap.plugin.api.model.TrackRoute
import nl.r1a.grassmap.plugin.api.model.TrackSegment

/**
 * One record a document yields, with the geometry a library record stores.
 *
 * A document has two families of record: its `<trk>` elements and its `<rte>`
 * elements (T-0411). [records] is the document's whole record order - every
 * track first, then every route - and a record's position in it is the index
 * its id is derived from, so the importer and the loader cannot disagree about
 * which record an id names.
 */
internal data class DocumentRecord(
    val kind: TrackKind,
    val track: Track,
)

/** The records of this document in id order. */
internal fun ImportedEntities.records(): List<DocumentRecord> =
    tracks.map { track -> DocumentRecord(TrackKind.Track, track) } +
        routes.map { route -> DocumentRecord(TrackKind.Route, route.asTrack()) }

/**
 * A route drawn as one segment. A route has no segment boundaries: the planner
 * produces one ordered line, and its stops are a separate record.
 */
internal fun TrackRoute.asTrack(): Track =
    Track(
        name = name,
        segments = listOf(TrackSegment(points)),
    )
