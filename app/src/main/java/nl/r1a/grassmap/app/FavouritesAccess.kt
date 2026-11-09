package nl.r1a.grassmap.app

import nl.r1a.grassmap.places.DeletedFavourite
import nl.r1a.grassmap.places.Favourite
import nl.r1a.grassmap.places.FavouriteGroup
import nl.r1a.grassmap.places.FavouriteId
import nl.r1a.grassmap.places.FavouriteImport
import nl.r1a.grassmap.places.FavouriteMutation
import nl.r1a.grassmap.places.FavouriteSpecial
import nl.r1a.grassmap.places.FavouriteUndo
import nl.r1a.grassmap.places.FavouriteUndoToken
import nl.r1a.grassmap.places.FavouritesSnapshot
import nl.r1a.grassmap.places.FavouritesStore
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File

/**
 * The app's handle on the favourites library. The file lives under the app's
 * private files root, and a build without one reports [AppError.NotReady]
 * rather than touching the disk.
 */
class FavouritesAccess internal constructor(
    root: File?,
) {
    private val store: FavouritesStore? = root?.let { FavouritesStore(File(it, FAVOURITES_PATH)) }

    suspend fun load(): Outcome<FavouritesSnapshot> = access { store -> store.load() }

    suspend fun add(favourite: Favourite): Outcome<Favourite> = access { store -> store.add(favourite) }

    suspend fun delete(id: FavouriteId): Outcome<DeletedFavourite> = access { store -> store.delete(id) }

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

    suspend fun export(): Outcome<ByteArray> = access { store -> store.export() }

    private suspend fun <T> access(block: suspend (FavouritesStore) -> Outcome<T>): Outcome<T> {
        val store = store ?: return Outcome.Failure(AppError.NotReady)
        return block(store)
    }

    private companion object {
        const val FAVOURITES_PATH = "favourites/favourites.gpx"
    }
}
