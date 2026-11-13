package nl.r1a.grassmap.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.TrackLibraryAccess
import nl.r1a.grassmap.plugin.api.host.TrackLibraryChange
import nl.r1a.grassmap.plugin.api.host.TrackLibraryUndo
import nl.r1a.grassmap.plugin.api.model.TrackFolderSummary
import nl.r1a.grassmap.plugin.api.model.TrackLibraryAction
import nl.r1a.grassmap.plugin.api.model.TrackLibraryEntry
import nl.r1a.grassmap.plugin.api.model.TrackLibraryState
import nl.r1a.grassmap.plugins.track.library.TrackFolder
import nl.r1a.grassmap.plugins.track.library.TrackFolderStore
import nl.r1a.grassmap.tracks.TrackId
import nl.r1a.grassmap.tracks.TrackMetadata
import nl.r1a.grassmap.tracks.TrackRemoval

/**
 * The host side of `plugins/track-library` (T-0414).
 *
 * It maps the track store and the folder index onto the library screen model
 * and sends every mutation through [TrackAccess] and [TrackFolderStore], so the
 * sidecar and the folder index are written before the plugin publishes a new
 * state. The GPX document is never rewritten.
 *
 * Undo stays scoped: each action returns one receipt and [undo] consumes that
 * one receipt. A deleted track is parked by the store, so its sidecar, its
 * metadata and its folder come back together. This is not the T-0304 undo
 * history.
 */
class StoreTrackLibrary(
    private val tracks: () -> TrackAccess,
    private val folders: TrackFolderStore,
) : TrackLibraryAccess {
    private val mutex = Mutex()
    private val pending = LinkedHashMap<String, Pending>()
    private var nextReceipt: Long = 1

    override suspend fun state(): Outcome<TrackLibraryState> =
        mutex.withLock {
            buildState(TrackLibraryAction.NONE)
        }

    override suspend fun createFolder(name: String): Outcome<TrackLibraryChange> =
        mutex.withLock {
            when (val created = folders.create(name)) {
                is Outcome.Failure -> {
                    created
                }

                is Outcome.Success -> {
                    val receipt = receipt("folder-create")
                    remember(receipt, Pending.RemoveFolder(created.value))
                    change(receipt, TrackLibraryAction.FOLDER_CREATED)
                }
            }
        }

    override suspend fun renameFolder(
        folderId: String,
        name: String,
    ): Outcome<TrackLibraryChange> =
        mutex.withLock {
            val existing = folders.find(folderId) ?: return@withLock Outcome.Failure(AppError.NotFound)
            when (val renamed = folders.rename(folderId, name)) {
                is Outcome.Failure -> {
                    renamed
                }

                is Outcome.Success -> {
                    val receipt = receipt("folder-rename")
                    remember(receipt, Pending.RestoreFolderName(folderId, existing.name))
                    change(receipt, TrackLibraryAction.FOLDER_RENAMED)
                }
            }
        }

    override suspend fun editTrack(
        trackId: String,
        name: String?,
        description: String?,
        folderId: String?,
    ): Outcome<TrackLibraryChange> =
        mutex.withLock {
            val summary =
                tracks().summaries().firstOrNull { summary -> summary.id == TrackId(trackId) }
                    ?: return@withLock Outcome.Failure(AppError.NotFound)
            // An unchanged folder id stays even when the folder index cannot
            // resolve its name: a display fallback must never become a write.
            val target =
                when {
                    folderId == summary.listId -> summary.listId
                    folderId == null -> null
                    folders.find(folderId) != null -> folderId
                    else -> return@withLock Outcome.Failure(AppError.NotFound)
                }
            val cleanedName = name?.trim()?.takeIf { it.isNotEmpty() }
            val cleanedDescription = description?.takeIf { it.isNotEmpty() }
            val metadata =
                TrackMetadata(
                    name = cleanedName,
                    description = cleanedDescription,
                    listId = target,
                )
            when (val written = tracks().setMetadata(TrackId(trackId), metadata)) {
                is Outcome.Failure -> {
                    written
                }

                is Outcome.Success -> {
                    val receipt = receipt("track-edit")
                    remember(
                        receipt,
                        Pending.RestoreMetadata(
                            trackId = trackId,
                            metadata = TrackMetadata(summary.name, summary.description, summary.listId),
                        ),
                    )
                    change(receipt, TrackLibraryAction.TRACK_EDITED)
                }
            }
        }

    override suspend fun moveTrack(
        trackId: String,
        folderId: String?,
    ): Outcome<TrackLibraryChange> =
        mutex.withLock {
            val target = folderId?.takeIf { id -> folders.find(id) != null }
            if (folderId != null && target == null) {
                return@withLock Outcome.Failure(AppError.NotFound)
            }
            val summary =
                tracks().summaries().firstOrNull { summary -> summary.id == TrackId(trackId) }
                    ?: return@withLock Outcome.Failure(AppError.NotFound)
            val metadata =
                TrackMetadata(
                    name = summary.name,
                    description = summary.description,
                    listId = target,
                )
            when (val written = tracks().setMetadata(TrackId(trackId), metadata)) {
                is Outcome.Failure -> {
                    written
                }

                is Outcome.Success -> {
                    val receipt = receipt("track-move")
                    remember(receipt, Pending.RestoreMetadata(trackId, TrackMetadata(summary.name, summary.description, summary.listId)))
                    change(receipt, TrackLibraryAction.TRACK_MOVED)
                }
            }
        }

    override suspend fun deleteTrack(trackId: String): Outcome<TrackLibraryChange> =
        mutex.withLock {
            when (val removed = tracks().removeToTrash(TrackId(trackId))) {
                is Outcome.Failure -> {
                    removed
                }

                is Outcome.Success -> {
                    val receipt = receipt("track-delete")
                    remember(receipt, Pending.RestoreTrack(removed.value))
                    change(receipt, TrackLibraryAction.TRACK_DELETED)
                }
            }
        }

    override suspend fun undo(receipt: String): Outcome<TrackLibraryUndo> =
        mutex.withLock {
            val action = pending[receipt] ?: return@withLock Outcome.Success(TrackLibraryUndo.NOT_APPLICABLE)
            when (val applied = applyUndo(action)) {
                is Outcome.Failure -> {
                    when (applied.error) {
                        // The action no longer applies: the folder or track is
                        // gone, or a re-import owns the id. Consume the
                        // receipt instead of leaving a dead undo on the stack.
                        AppError.NotFound, AppError.InvalidInput, AppError.CorruptData -> {
                            pending.remove(receipt)
                            Outcome.Success(TrackLibraryUndo.NOT_APPLICABLE)
                        }

                        else -> {
                            applied
                        }
                    }
                }

                is Outcome.Success -> {
                    pending.remove(receipt)
                    Outcome.Success(TrackLibraryUndo.APPLIED)
                }
            }
        }

    private suspend fun applyUndo(action: Pending): Outcome<Unit> =
        when (action) {
            is Pending.RemoveFolder -> folders.remove(action.folder.id)
            is Pending.RestoreFolderName -> folders.rename(action.folderId, action.name).mapUnit()
            is Pending.RestoreTrack -> tracks().restoreFromTrash(action.removal)
            is Pending.RestoreMetadata -> tracks().setMetadata(TrackId(action.trackId), action.metadata)
        }

    private suspend fun change(
        receipt: String,
        action: TrackLibraryAction,
    ): Outcome<TrackLibraryChange> =
        when (val built = buildState(action)) {
            is Outcome.Failure -> {
                built
            }

            is Outcome.Success -> {
                Outcome.Success(TrackLibraryChange(state = built.value, receipt = receipt))
            }
        }

    private suspend fun buildState(action: TrackLibraryAction): Outcome<TrackLibraryState> {
        val summaries = tracks().summaries()
        val folderList = folders.load()
        // The sidecar's folder id is kept even when the index cannot name it;
        // only the display falls back to no folder name.
        val entries =
            summaries.map { summary ->
                TrackLibraryEntry(
                    id = summary.id.value,
                    name = summary.name,
                    listId = summary.listId,
                    distanceMeters = summary.analysis.distanceMeters,
                    ascentMeters = summary.analysis.ascentMeters,
                    durationMillis = summary.analysis.durationMillis,
                    savedAtMillis = summary.savedAtMillis,
                    description = summary.description,
                    colour = summary.style.colour,
                    startLatitude = summary.startLatitude,
                    startLongitude = summary.startLongitude,
                )
            }
        val counts = entries.groupingBy { entry -> entry.listId }.eachCount()
        val distances =
            entries.groupBy { entry -> entry.listId }.mapValues { (_, list) ->
                list.sumOf { entry -> entry.distanceMeters }
            }
        return Outcome.Success(
            TrackLibraryState(
                folders =
                    folderList.map { folder ->
                        TrackFolderSummary(
                            id = folder.id,
                            name = folder.name,
                            trackCount = counts[folder.id] ?: 0,
                            distanceMeters = distances[folder.id] ?: 0.0,
                        )
                    },
                tracks = entries,
                lastAction = action,
            ),
        )
    }

    private fun remember(
        receipt: String,
        action: Pending,
    ) {
        pending[receipt] = action
        while (pending.size > MAX_UNDO_STEPS) {
            val oldest = pending.keys.firstOrNull() ?: break
            pending.remove(oldest)
        }
    }

    private fun receipt(prefix: String): String = prefix + ":" + nextReceipt++

    private sealed interface Pending {
        data class RemoveFolder(
            val folder: TrackFolder,
        ) : Pending

        data class RestoreFolderName(
            val folderId: String,
            val name: String,
        ) : Pending

        data class RestoreTrack(
            val removal: TrackRemoval,
        ) : Pending

        data class RestoreMetadata(
            val trackId: String,
            val metadata: TrackMetadata,
        ) : Pending
    }

    private companion object {
        /** The scoped stack is small on purpose; one step is the promised undo. */
        const val MAX_UNDO_STEPS = 20
    }
}

private fun <T> Outcome<T>.mapUnit(): Outcome<Unit> =
    when (this) {
        is Outcome.Success -> Outcome.Success(Unit)
        is Outcome.Failure -> this
    }
