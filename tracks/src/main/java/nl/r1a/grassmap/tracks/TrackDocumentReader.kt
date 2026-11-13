package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.ImportedEntities

/**
 * Reads one stored document back into everything it holds. The app supplies it
 * from `file.gpx`.
 *
 * The store needs the routes and waypoints as well as the tracks: a saved route
 * is a `<rte>` and an imported waypoint belongs to its document (T-0411), so a
 * reader that returned only tracks would lose both on the next load.
 */
fun interface TrackDocumentReader {
    suspend fun read(bytes: ByteArray): Outcome<ImportedEntities>
}
