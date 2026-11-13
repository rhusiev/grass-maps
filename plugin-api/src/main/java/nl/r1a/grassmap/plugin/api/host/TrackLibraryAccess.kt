package nl.r1a.grassmap.plugin.api.host

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.TrackLibraryState

/** One applied track library action: the new screen state and its undo receipt. */
data class TrackLibraryChange(
    val state: TrackLibraryState,
    val receipt: String,
)

/** The result of consuming one scoped undo receipt. */
enum class TrackLibraryUndo {
    APPLIED,

    /** The action no longer applies; the library is unchanged. */
    NOT_APPLICABLE,
}

/**
 * The persistence side of the track library (T-0414).
 *
 * `plugins/track-library` owns the screen state, the four fixed orders, the
 * name search and the scoped undo receipts. The host implements this access
 * over the existing track store: the per-track name, description and folder id
 * live on the track sidecar, and the folder definitions live in the library
 * index beside the documents. No GPX document is rewritten.
 *
 * Undo is scoped to one action, like the favourites delete receipt; it is not
 * the global T-0304 history.
 */
interface TrackLibraryAccess {
    suspend fun state(): Outcome<TrackLibraryState>

    suspend fun createFolder(name: String): Outcome<TrackLibraryChange>

    suspend fun renameFolder(
        folderId: String,
        name: String,
    ): Outcome<TrackLibraryChange>

    /**
     * Renames, describes and files one track in one action; a null
     * [description] clears it and a null [folderId] unfiles it.
     */
    suspend fun editTrack(
        trackId: String,
        name: String?,
        description: String?,
        folderId: String?,
    ): Outcome<TrackLibraryChange>

    /** Moves a track to a folder, or out of every folder when [folderId] is null. */
    suspend fun moveTrack(
        trackId: String,
        folderId: String?,
    ): Outcome<TrackLibraryChange>

    /** Deletes one imported track; the receipt restores it to its folder. */
    suspend fun deleteTrack(trackId: String): Outcome<TrackLibraryChange>

    /**
     * Consumes [receipt]. A receipt whose action cannot apply any more is
     * [TrackLibraryUndo.NOT_APPLICABLE] and changes nothing.
     */
    suspend fun undo(receipt: String): Outcome<TrackLibraryUndo>
}

/** The access a build without a track library wires. */
object NoTrackLibraryAccess : TrackLibraryAccess {
    override suspend fun state(): Outcome<TrackLibraryState> = notReady()

    override suspend fun createFolder(name: String): Outcome<TrackLibraryChange> = notReady()

    override suspend fun renameFolder(
        folderId: String,
        name: String,
    ): Outcome<TrackLibraryChange> = notReady()

    override suspend fun editTrack(
        trackId: String,
        name: String?,
        description: String?,
        folderId: String?,
    ): Outcome<TrackLibraryChange> = notReady()

    override suspend fun moveTrack(
        trackId: String,
        folderId: String?,
    ): Outcome<TrackLibraryChange> = notReady()

    override suspend fun deleteTrack(trackId: String): Outcome<TrackLibraryChange> = notReady()

    override suspend fun undo(receipt: String): Outcome<TrackLibraryUndo> = notReady()

    private fun notReady(): Outcome.Failure = Outcome.Failure(AppError.NotReady)
}
