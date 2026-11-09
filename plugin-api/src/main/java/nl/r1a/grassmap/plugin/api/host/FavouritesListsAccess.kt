package nl.r1a.grassmap.plugin.api.host

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.FavouriteListsState
import nl.r1a.grassmap.plugin.api.model.FavouriteSpecial

/** One applied lists action: the new screen state and the receipt that undoes it. */
data class FavouriteListChange(
    val state: FavouriteListsState,
    val receipt: String,
)

/** The result of consuming one scoped undo receipt. */
enum class FavouriteListUndo {
    APPLIED,

    /** The action no longer applies; the library is unchanged. */
    NOT_APPLICABLE,
}

/**
 * The persistence side of the lists plugin (T-0213).
 *
 * `plugins/favourites-ui` owns the lists state, the action commands and the
 * scoped undo receipts. The host implements this access over the merged
 * favourites library, so every mutation writes `favourites.gpx` and its group
 * definitions before the plugin publishes the new state. The plugin never
 * parses GPX and there is no second store.
 *
 * Undo is scoped to one action, like the favourites delete receipt; it is not
 * the global T-0304 history.
 */
interface FavouritesListsAccess {
    suspend fun state(): Outcome<FavouriteListsState>

    suspend fun createList(
        name: String,
        color: String,
        icon: String?,
    ): Outcome<FavouriteListChange>

    suspend fun movePoint(
        pointId: String,
        list: String,
    ): Outcome<FavouriteListChange>

    suspend fun recolourList(
        name: String,
        color: String,
    ): Outcome<FavouriteListChange>

    suspend fun setSpecial(
        pointId: String,
        special: FavouriteSpecial,
    ): Outcome<FavouriteListChange>

    /**
     * Consumes [receipt]. A receipt whose action cannot apply any more is
     * [FavouriteListUndo.NOT_APPLICABLE] and changes nothing.
     */
    suspend fun undo(receipt: String): Outcome<FavouriteListUndo>
}

/** The access a build without a favourites library wires. */
object NoFavouritesListsAccess : FavouritesListsAccess {
    override suspend fun state(): Outcome<FavouriteListsState> = notReady()

    override suspend fun createList(
        name: String,
        color: String,
        icon: String?,
    ): Outcome<FavouriteListChange> = notReady()

    override suspend fun movePoint(
        pointId: String,
        list: String,
    ): Outcome<FavouriteListChange> = notReady()

    override suspend fun recolourList(
        name: String,
        color: String,
    ): Outcome<FavouriteListChange> = notReady()

    override suspend fun setSpecial(
        pointId: String,
        special: FavouriteSpecial,
    ): Outcome<FavouriteListChange> = notReady()

    override suspend fun undo(receipt: String): Outcome<FavouriteListUndo> = notReady()

    private fun notReady(): Outcome.Failure = Outcome.Failure(AppError.NotReady)
}
