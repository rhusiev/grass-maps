package nl.r1a.grassmap.tracks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackKind
import nl.r1a.grassmap.plugin.api.model.TrackStyle
import nl.r1a.grassmap.plugin.api.model.TrackWaypoint

/** A stored track's identity. T-0027 derives it from the imported file bytes. */
@JvmInline
value class TrackId(
    val value: String,
) {
    override fun toString(): String = value
}

/**
 * The first point of a track, or null when every segment is empty. The library's
 * Nearest order measures from this point (T-0414).
 */
internal fun Track.startPoint(): GeoPoint? =
    segments
        .firstOrNull { segment -> segment.points.isNotEmpty() }
        ?.points
        ?.first()
        ?.position

/**
 * One imported track with the profile computed when it was stored.
 * [elevation] records the DEM correction pass that produced [track], or null
 * when the caller built the record without one.
 *
 * [kind] says whether the record is a track or a saved route (T-0411), and
 * [recompute] is the request a saved route was built from, or null when the
 * build has none - an imported `<rte>` has no sidecar, and an unreadable one
 * degrades to the stored line rather than failing the open. [waypoints] are the
 * document's `<wpt>` entries, kept so an export writes them back.
 */
data class StoredTrack(
    val id: TrackId,
    val name: String?,
    val track: Track,
    val analysis: TrackAnalysis,
    val style: TrackStyle = TrackStyle(),
    val trackIndex: Int = 0,
    val elevation: ElevationCorrectionReport? = null,
    val description: String? = null,
    val listId: String? = null,
    val kind: TrackKind = TrackKind.Track,
    val recompute: PlannedRouteRecompute? = null,
    val waypoints: List<TrackWaypoint> = emptyList(),
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
    val description: String? = null,
    val listId: String? = null,
    val startLatitude: Double? = null,
    val startLongitude: Double? = null,
    val kind: TrackKind = TrackKind.Track,
)

/**
 * The library metadata a sidecar carries beside the imported document
 * (T-0414). [description] is stored as it arrived and may carry simple HTML,
 * so a renderer sanitises it before a screen draws it. [listId] is the folder
 * the track belongs to, or null when it is unfiled. The GPX document is never
 * rewritten for a metadata change.
 */
data class TrackMetadata(
    val name: String?,
    val description: String?,
    val listId: String?,
)

/** One removed track parked for its one-step undo (T-0414). */
@JvmInline
value class TrackRemoval(
    val token: String,
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

    /**
     * Replaces the name, description and folder of one track and rewrites its
     * sidecar. The stored document is untouched.
     */
    suspend fun setMetadata(
        id: TrackId,
        metadata: TrackMetadata,
    ): Outcome<Unit>

    /** Removes a track and its document when no other track uses that document. */
    suspend fun remove(id: TrackId): Outcome<Unit>

    /**
     * Removes a track but keeps its sidecar and document for
     * [restoreFromTrash]. The parked files survive a reload; the token is
     * process-scoped, and this is the library screen's one-step undo rather
     * than a quarantine that an older build reads back.
     */
    suspend fun removeToTrash(id: TrackId): Outcome<TrackRemoval>

    /** Puts a removed track, its metadata and its folder back. */
    suspend fun restoreFromTrash(removal: TrackRemoval): Outcome<Unit>

    /** Re-reads a directory-backed store; an in-memory store has nothing to do. */
    suspend fun reload(): Outcome<Unit>

    /** The tracks the map draws, current after every save, remove and style change. */
    val drawn: StateFlow<List<MapTrack>>

    /**
     * Increases after every write that changes the library. A reader that
     * cached [summaries] reloads when it sees a new value, so a write from a
     * path the reader does not own - the share-sheet import (T-0416) is one -
     * still reaches it.
     */
    val revision: StateFlow<Long>
}

/** The import path over [TrackStore]; T-0401 owns the file store, T-0414 the UI. */
class InMemoryTrackStore(
    private val clock: () -> Long = { 0L },
) : TrackStore {
    private val mutex = Mutex()
    private val tracks = LinkedHashMap<TrackId, StoredTrack>()
    private val savedAt = mutableMapOf<TrackId, Long>()
    private val sources = mutableMapOf<TrackId, ByteArray>()
    private val trash = LinkedHashMap<String, TrashedTrack>()
    private var nextTrashToken = 1L
    private val drawnState = MutableStateFlow<List<MapTrack>>(emptyList())
    private val revisionState = MutableStateFlow(0L)

    override val drawn: StateFlow<List<MapTrack>> = drawnState.asStateFlow()

    override val revision: StateFlow<Long> = revisionState.asStateFlow()

    override suspend fun save(
        track: StoredTrack,
        source: ByteArray?,
    ): Outcome<StoredTrack> =
        mutex.withLock {
            val existing = tracks[track.id]
            val effective =
                track.withPreservedStyle(existing).copy(
                    name = existing?.name ?: track.name,
                    description = existing?.description ?: track.description,
                    listId = existing?.listId ?: track.listId,
                )
            tracks[track.id] = effective
            savedAt[track.id] = clock()
            if (source != null) {
                sources[track.id] = source
            }
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
                val start = track.track.startPoint()
                TrackSummary(
                    id = track.id,
                    name = track.name,
                    style = track.style,
                    analysis = track.analysis,
                    savedAtMillis = savedAt[track.id] ?: 0L,
                    trackIndex = track.trackIndex,
                    description = track.description,
                    listId = track.listId,
                    startLatitude = start?.latitude,
                    startLongitude = start?.longitude,
                    kind = track.kind,
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

    override suspend fun setMetadata(
        id: TrackId,
        metadata: TrackMetadata,
    ): Outcome<Unit> =
        mutex.withLock {
            if (!metadata.isValid()) {
                return@withLock Outcome.Failure(AppError.InvalidInput)
            }
            val existing = tracks[id] ?: return@withLock Outcome.Failure(AppError.NotFound)
            val cleaned = metadata.cleaned()
            tracks[id] =
                existing.copy(
                    name = cleaned.name,
                    description = cleaned.description,
                    listId = cleaned.listId,
                )
            publish()
            Outcome.Success(Unit)
        }

    override suspend fun remove(id: TrackId): Outcome<Unit> =
        mutex.withLock {
            if (tracks.remove(id) == null) {
                return@withLock Outcome.Failure(AppError.NotFound)
            }
            savedAt.remove(id)
            sources.remove(id)
            publish()
            Outcome.Success(Unit)
        }

    override suspend fun removeToTrash(id: TrackId): Outcome<TrackRemoval> =
        mutex.withLock {
            val record = tracks.remove(id) ?: return@withLock Outcome.Failure(AppError.NotFound)
            val saved = savedAt.remove(id) ?: 0L
            val source = sources.remove(id)
            val token = "memory:" + nextTrashToken++
            trash[token] = TrashedTrack(record, saved, source)
            publish()
            Outcome.Success(TrackRemoval(token))
        }

    override suspend fun restoreFromTrash(removal: TrackRemoval): Outcome<Unit> =
        mutex.withLock {
            val trashed = trash[removal.token] ?: return@withLock Outcome.Failure(AppError.NotFound)
            if (tracks.containsKey(trashed.record.id)) {
                // A re-import after the delete owns the id now; the old receipt
                // must not overwrite the newer record.
                return@withLock Outcome.Failure(AppError.InvalidInput)
            }
            trash.remove(removal.token)
            tracks[trashed.record.id] = trashed.record
            savedAt[trashed.record.id] = trashed.savedAtMillis
            if (trashed.source != null) {
                sources[trashed.record.id] = trashed.source
            }
            publish()
            Outcome.Success(Unit)
        }

    override suspend fun reload(): Outcome<Unit> = Outcome.Success(Unit)

    private fun publish() {
        drawnState.value = ordered().map { it.toMapTrack() }
        revisionState.value = revisionState.value + 1
    }

    private fun ordered(): List<StoredTrack> = tracks.values.sortedBy { it.id.value }

    private data class TrashedTrack(
        val record: StoredTrack,
        val savedAtMillis: Long,
        val source: ByteArray?,
    )
}

/**
 * A metadata value is bounded before it reaches a sidecar. The name is trimmed
 * to null when blank; the description keeps its markup but not an unbounded
 * size; a blank folder id means unfiled.
 */
internal fun TrackMetadata.isValid(): Boolean =
    name.orEmpty().length <= MAX_TRACK_NAME_LENGTH &&
        description.orEmpty().length <= MAX_TRACK_DESCRIPTION_LENGTH &&
        listId.orEmpty().length <= MAX_TRACK_LIST_ID_LENGTH

internal fun TrackMetadata.cleaned(): TrackMetadata =
    TrackMetadata(
        name = name.cleanedName(),
        description = description.cleanedDescription(),
        listId = listId.cleanedListId(),
    )

internal fun String?.cleanedName(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

internal fun String?.cleanedDescription(): String? = this?.takeIf { it.isNotEmpty() }

internal fun String?.cleanedListId(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

internal const val MAX_TRACK_NAME_LENGTH: Int = 200
internal const val MAX_TRACK_DESCRIPTION_LENGTH: Int = 20_000
internal const val MAX_TRACK_LIST_ID_LENGTH: Int = 200

internal fun StoredTrack.withPreservedStyle(existing: StoredTrack?): StoredTrack =
    when {
        existing == null -> this
        existing.style == style -> this
        else -> copy(style = existing.style)
    }
