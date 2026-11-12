package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.Track

/** Reads one stored document back into its tracks. The app supplies it from `file.gpx`. */
fun interface TrackDocumentReader {
    suspend fun read(bytes: ByteArray): Outcome<List<Track>>
}
