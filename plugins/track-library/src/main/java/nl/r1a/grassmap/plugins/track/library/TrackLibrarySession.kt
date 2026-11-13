package nl.r1a.grassmap.plugins.track.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.TrackLibraryAccess
import nl.r1a.grassmap.plugin.api.host.TrackLibraryChange
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.TrackLibraryAction
import nl.r1a.grassmap.plugin.api.model.TrackLibraryState
import nl.r1a.grassmap.plugin.api.model.TrackSort

/**
 * The library state and its actions inside `plugins/track-library` (T-0414).
 *
 * Each action returns a state plus a scoped undo receipt, which this session
 * keeps on a small stack. [undo] consumes the latest receipt; an action that no
 * longer applies is [nl.r1a.grassmap.plugin.api.host.TrackLibraryUndo.NOT_APPLICABLE],
 * not an error. This is not the global T-0304 undo history.
 *
 * Actions are serialised: one library mutation is in flight at a time, so the
 * published state always matches the persisted sidecars and folder index.
 *
 * The search, the folder filter and the four fixed orders only change the
 * derived [TrackLibraryState.browse] view; the library itself is untouched.
 * Keeping them here means the shell never duplicates the rules.
 */
class TrackLibrarySession(
    private val access: TrackLibraryAccess,
) {
    private val mutex = Mutex()
    private val receipts = ArrayDeque<String>()
    private val current = MutableStateFlow(TrackLibraryState())
    private var query: String = ""
    private var lastQuerySequence: Long = 0
    private var sort: TrackSort = TrackSort.NEWEST
    private var origin: GeoPoint? = null
    private var selectedListId: String? = null

    val state: StateFlow<TrackLibraryState> = current.asStateFlow()

    /** Loads the persisted library; a missing library leaves the empty state. */
    suspend fun load(): Outcome<Unit> =
        mutex.withLock {
            when (val outcome = access.state()) {
                is Outcome.Failure -> {
                    outcome
                }

                is Outcome.Success -> {
                    publish(outcome.value)
                    Outcome.Success(Unit)
                }
            }
        }

    suspend fun createFolder(name: String): Outcome<TrackLibraryState> = mutate { access.createFolder(name) }

    suspend fun renameFolder(
        folderId: String,
        name: String,
    ): Outcome<TrackLibraryState> = mutate { access.renameFolder(folderId, name) }

    suspend fun editTrack(
        trackId: String,
        name: String?,
        description: String?,
        folderId: String?,
    ): Outcome<TrackLibraryState> = mutate { access.editTrack(trackId, name, description, folderId) }

    suspend fun moveTrack(
        trackId: String,
        folderId: String?,
    ): Outcome<TrackLibraryState> = mutate { access.moveTrack(trackId, folderId) }

    suspend fun deleteTrack(trackId: String): Outcome<TrackLibraryState> = mutate { access.deleteTrack(trackId) }

    /**
     * Applies a search query to the derived browse view; the library is
     * untouched. [sequence] orders keystrokes, so a slow earlier one cannot
     * overwrite a later one.
     */
    suspend fun setQuery(
        text: String,
        sequence: Long = 0,
    ): Outcome<TrackLibraryState> =
        mutex.withLock {
            if (sequence in 1..lastQuerySequence) {
                return@withLock Outcome.Success(current.value)
            }
            if (sequence > lastQuerySequence) {
                lastQuerySequence = sequence
            }
            query = text.trim()
            republishBrowse()
            Outcome.Success(current.value)
        }

    /**
     * Applies one of the four fixed orders; the library is untouched. [origin]
     * is the position Nearest measures from, normally the map centre. Without
     * one, Nearest falls back to Name A-Z in the browser.
     */
    suspend fun setSort(
        sort: TrackSort,
        origin: GeoPoint? = null,
    ): Outcome<TrackLibraryState> =
        mutex.withLock {
            this.sort = sort
            this.origin = origin
            republishBrowse()
            Outcome.Success(current.value)
        }

    /** Filters the rows to one folder, or to every folder when [folderId] is null. */
    suspend fun selectFolder(folderId: String?): Outcome<TrackLibraryState> =
        mutex.withLock {
            selectedListId = folderId?.takeIf { id -> current.value.folders.any { folder -> folder.id == id } }
            republishBrowse()
            Outcome.Success(current.value)
        }

    /**
     * Undoes the latest action. With no receipt it returns the current state
     * unchanged, so the command is safe to call twice.
     */
    suspend fun undo(): Outcome<TrackLibraryState> =
        mutex.withLock {
            val receipt = receipts.removeLastOrNull() ?: return@withLock Outcome.Success(current.value)
            when (val outcome = access.undo(receipt)) {
                is Outcome.Failure -> {
                    receipts.addLast(receipt)
                    outcome
                }

                is Outcome.Success -> {
                    when (val loaded = access.state()) {
                        is Outcome.Failure -> {
                            // The undo applied, so the receipt stays consumed.
                            current.value =
                                current.value.copy(
                                    undoAvailable = receipts.isNotEmpty(),
                                    lastAction =
                                        if (receipts.isEmpty()) {
                                            TrackLibraryAction.NONE
                                        } else {
                                            current.value.lastAction
                                        },
                                )
                            Outcome.Success(current.value)
                        }

                        is Outcome.Success -> {
                            publish(loaded.value)
                            Outcome.Success(current.value)
                        }
                    }
                }
            }
        }

    private suspend fun mutate(action: suspend () -> Outcome<TrackLibraryChange>): Outcome<TrackLibraryState> =
        mutex.withLock {
            when (val outcome = action()) {
                is Outcome.Failure -> {
                    outcome
                }

                is Outcome.Success -> {
                    pushReceipt(outcome.value.receipt)
                    // Every publish path goes through here, so the derived browse
                    // view can never be left stale by a library action.
                    publish(outcome.value.state)
                    Outcome.Success(current.value)
                }
            }
        }

    private fun pushReceipt(receipt: String) {
        receipts.addLast(receipt)
        while (receipts.size > MAX_RECEIPTS) {
            receipts.removeFirst()
        }
    }

    private fun publish(state: TrackLibraryState) {
        val folders = state.folders
        if (selectedListId != null && folders.none { folder -> folder.id == selectedListId }) {
            selectedListId = null
        }
        current.value =
            state.copy(
                undoAvailable = receipts.isNotEmpty(),
                lastAction = if (receipts.isEmpty()) TrackLibraryAction.NONE else state.lastAction,
                browse = browse(state),
            )
    }

    /** Recomputes the derived browse view without changing the library state. */
    private fun republishBrowse() {
        val state = current.value
        current.value = state.copy(browse = browse(state))
    }

    private fun browse(state: TrackLibraryState) =
        TrackLibraryBrowser.browse(
            tracks = state.tracks,
            folders = state.folders,
            query = query,
            sort = sort,
            selectedListId = selectedListId,
            origin = origin,
        )

    private companion object {
        const val MAX_RECEIPTS = 20
    }
}
