package nl.r1a.grassmap.tracks

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.Track

/** A stored track's identity. T-0027 derives it from the imported file bytes. */
@JvmInline
value class TrackId(
    val value: String,
) {
    override fun toString(): String = value
}

/** One imported track with the profile computed when it was stored. */
data class StoredTrack(
    val id: TrackId,
    val name: String?,
    val track: Track,
    val analysis: TrackAnalysis,
)

/**
 * The track library behind the import path. T-0027 stores in memory; the file
 * store, folders and undo land in T-0401/T-0414. Every call is a value, so a
 * missing track is [AppError.NotFound] and never an exception.
 */
interface TrackStore {
    suspend fun save(track: StoredTrack): Outcome<Unit>

    suspend fun get(id: TrackId): Outcome<StoredTrack>

    suspend fun all(): List<StoredTrack>
}

/** A process-scoped track store. It survives an Activity recreation. */
class InMemoryTrackStore : TrackStore {
    private val mutex = Mutex()
    private val tracks = mutableMapOf<TrackId, StoredTrack>()

    override suspend fun save(track: StoredTrack): Outcome<Unit> =
        mutex.withLock {
            tracks[track.id] = track
            Outcome.Success(Unit)
        }

    override suspend fun get(id: TrackId): Outcome<StoredTrack> =
        mutex.withLock {
            tracks[id]?.let { Outcome.Success(it) } ?: Outcome.Failure(AppError.NotFound)
        }

    override suspend fun all(): List<StoredTrack> =
        mutex.withLock {
            tracks.values.toList()
        }
}
