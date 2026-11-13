package nl.r1a.grassmap.tracks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackStyle

/** A stored track's identity. T-0027 derives it from the imported file bytes. */
@JvmInline
value class TrackId(
    val value: String,
) {
    override fun toString(): String = value
}

/**
 * One imported track with the profile computed when it was stored.
 * [elevation] records the DEM correction pass that produced [track], or null
 * when the caller built the record without one.
 */
data class StoredTrack(
    val id: TrackId,
    val name: String?,
    val track: Track,
    val analysis: TrackAnalysis,
    val style: TrackStyle = TrackStyle(),
    val trackIndex: Int = 0,
    val elevation: ElevationCorrectionReport? = null,
) {
    /** The drawable form the map layer reads; geometry only, no profile. */
    fun toMapTrack(): MapTrack =
        MapTrack(
            id = id.value,
            name = name,
            style = style,
            segments = track.segments.map { segment -> segment.points.map { point -> point.position } },
        )
}

/**
 * A cheap library row: the sidecar contents without the parsed geometry. The
 * library screen (T-0414) lists these and asks [TrackStore.get] for one track.
 */
data class TrackSummary(
    val id: TrackId,
    val name: String?,
    val style: TrackStyle,
    val analysis: TrackAnalysis,
    val savedAtMillis: Long,
    val trackIndex: Int = 0,
)

/**
 * The track library. A save is idempotent for the same bytes: the id is the
 * document hash, so importing the same file twice updates one record instead of
 * adding a second.
 *
 * Every call is a value, so a missing track is [AppError.NotFound] and never an
 * exception. Implementations do file I/O off the caller's thread when they own
 * a directory.
 */
interface TrackStore {
    /**
     * Writes [track] and its [source] document. [source] is required for a track
     * the store does not have yet; an existing record keeps its style. The
     * returned record is the effective one, so a re-import reports the colour the
     * library already used.
     */
    suspend fun save(
        track: StoredTrack,
        source: ByteArray? = null,
    ): Outcome<StoredTrack>

    suspend fun get(id: TrackId): Outcome<StoredTrack>

    /** Every stored track, ordered by id. */
    suspend fun all(): List<StoredTrack>

    /** Every stored track's sidecar row, ordered by id. */
    suspend fun summaries(): List<TrackSummary>

    /** Replaces the styling of one track and rewrites its sidecar. */
    suspend fun setStyle(
        id: TrackId,
        style: TrackStyle,
    ): Outcome<Unit>

    /** Removes a track and its document when no other track uses that document. */
    suspend fun remove(id: TrackId): Outcome<Unit>

    /** Re-reads a directory-backed store; an in-memory store has nothing to do. */
    suspend fun reload(): Outcome<Unit>

    /** The tracks the map draws, current after every save, remove and style change. */
    val drawn: StateFlow<List<MapTrack>>
}

/** The import path over [TrackStore]; T-0401 owns the file store, T-0414 the UI. */
class InMemoryTrackStore : TrackStore {
    private val mutex = Mutex()
    private val tracks = LinkedHashMap<TrackId, StoredTrack>()
    private val savedAt = mutableMapOf<TrackId, Long>()
    private val drawnState = MutableStateFlow<List<MapTrack>>(emptyList())

    override val drawn: StateFlow<List<MapTrack>> = drawnState.asStateFlow()

    override suspend fun save(
        track: StoredTrack,
        source: ByteArray?,
    ): Outcome<StoredTrack> =
        mutex.withLock {
            val effective = track.withPreservedStyle(tracks[track.id])
            tracks[track.id] = effective
            savedAt.putIfAbsent(track.id, 0L)
            publish()
            Outcome.Success(effective)
        }

    override suspend fun get(id: TrackId): Outcome<StoredTrack> =
        mutex.withLock {
            tracks[id]?.let { Outcome.Success(it) } ?: Outcome.Failure(AppError.NotFound)
        }

    override suspend fun all(): List<StoredTrack> = mutex.withLock { ordered() }

    override suspend fun summaries(): List<TrackSummary> =
        mutex.withLock {
            ordered().map { track ->
                TrackSummary(
                    id = track.id,
                    name = track.name,
                    style = track.style,
                    analysis = track.analysis,
                    savedAtMillis = savedAt[track.id] ?: 0L,
                    trackIndex = track.trackIndex,
                )
            }
        }

    override suspend fun setStyle(
        id: TrackId,
        style: TrackStyle,
    ): Outcome<Unit> =
        mutex.withLock {
            if (!style.isValid()) {
                return@withLock Outcome.Failure(AppError.InvalidInput)
            }
            val existing = tracks[id] ?: return@withLock Outcome.Failure(AppError.NotFound)
            tracks[id] = existing.copy(style = style)
            publish()
            Outcome.Success(Unit)
        }

    override suspend fun remove(id: TrackId): Outcome<Unit> =
        mutex.withLock {
            if (tracks.remove(id) == null) {
                return@withLock Outcome.Failure(AppError.NotFound)
            }
            savedAt.remove(id)
            publish()
            Outcome.Success(Unit)
        }

    override suspend fun reload(): Outcome<Unit> = Outcome.Success(Unit)

    private fun publish() {
        drawnState.value = ordered().map { it.toMapTrack() }
    }

    private fun ordered(): List<StoredTrack> = tracks.values.sortedBy { it.id.value }
}

internal fun StoredTrack.withPreservedStyle(existing: StoredTrack?): StoredTrack =
    when {
        existing == null -> this
        existing.style == style -> this
        else -> copy(style = existing.style)
    }
