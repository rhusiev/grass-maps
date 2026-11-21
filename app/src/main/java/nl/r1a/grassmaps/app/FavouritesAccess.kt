package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.places.DeletedFavourite
import nl.r1a.grassmaps.places.Favourite
import nl.r1a.grassmaps.places.FavouriteDocument
import nl.r1a.grassmaps.places.FavouriteGroup
import nl.r1a.grassmaps.places.FavouriteId
import nl.r1a.grassmaps.places.FavouriteImport
import nl.r1a.grassmaps.places.FavouriteMutation
import nl.r1a.grassmaps.places.FavouriteSpecial
import nl.r1a.grassmaps.places.FavouriteUndo
import nl.r1a.grassmaps.places.FavouriteUndoToken
import nl.r1a.grassmaps.places.FavouritesSnapshot
import nl.r1a.grassmaps.places.FavouritesStore
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import java.io.File

/**
 * The app's handle on the favourites library. The file lives under the app's
 * private files root, and a build without one reports [AppError.NotReady]
 * rather than touching the disk.
 */
class FavouritesAccess internal constructor(
    root: File?,
) {
    private val store: FavouritesStore? = root?.let { FavouritesStore(File(it, UserDataLayout.FAVOURITES_PATH)) }

    suspend fun load(): Outcome<FavouritesSnapshot> = access { store -> store.load() }

    suspend fun add(favourite: Favourite): Outcome<Favourite> = access { store -> store.add(favourite) }

    suspend fun delete(id: FavouriteId): Outcome<DeletedFavourite> = access { store -> store.delete(id) }

    /** Renames one saved place; the store returns the point's new derived id. */
    suspend fun rename(
        id: FavouriteId,
        name: String,
    ): Outcome<Favourite> = access { store -> store.rename(id, name) }

    suspend fun undoDelete(id: FavouriteId): Outcome<Favourite> = access { store -> store.undoDelete(id) }

    suspend fun createGroup(
        name: String,
        color: String,
        icon: String?,
    ): Outcome<FavouriteMutation<FavouriteGroup>> = access { store -> store.createGroup(name, color, icon) }

    suspend fun moveFavourite(
        id: FavouriteId,
        group: String,
    ): Outcome<FavouriteMutation<Favourite>> = access { store -> store.moveFavourite(id, group) }

    suspend fun recolourGroup(
        name: String,
        color: String,
    ): Outcome<FavouriteMutation<FavouriteGroup>> = access { store -> store.recolourGroup(name, color) }

    suspend fun setSpecial(
        id: FavouriteId,
        special: FavouriteSpecial,
    ): Outcome<FavouriteMutation<Favourite>> = access { store -> store.setSpecial(id, special) }

    suspend fun undoListAction(token: FavouriteUndoToken): Outcome<FavouriteUndo> = access { store -> store.undoListAction(token) }

    suspend fun import(bytes: ByteArray): Outcome<FavouriteImport> = access { store -> store.import(bytes) }

    /** Replaces the whole library from a merged snapshot; the store validates and writes it atomically. */
    suspend fun save(snapshot: FavouritesSnapshot): Outcome<Unit> = access { store -> store.save(snapshot) }

    /** Reads a favourites document without writing it, for a bundle import plan. */
    suspend fun parse(bytes: ByteArray): Outcome<FavouriteDocument> =
        when (val target = store) {
            null -> Outcome.Failure(AppError.NotReady)
            else -> target.parse(bytes)
        }

    /**
     * Replaces the whole library with [document], a backup copy. The store's
     * mutex orders it against every mutation, and a document that does not parse
     * changes nothing.
     */
    suspend fun restore(document: ByteArray): Outcome<Unit> = access { store -> store.replace(document) }

    suspend fun export(): Outcome<ByteArray> = access { store -> store.export() }

    private suspend fun <T> access(block: suspend (FavouritesStore) -> Outcome<T>): Outcome<T> {
        val store = store ?: return Outcome.Failure(AppError.NotReady)
        return block(store)
    }
}
