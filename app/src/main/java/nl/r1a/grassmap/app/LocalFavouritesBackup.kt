package nl.r1a.grassmap.app

import kotlinx.coroutines.CoroutineScope
import nl.r1a.grassmap.core.services.FavouritesBackupCopy
import nl.r1a.grassmap.core.services.FavouritesBackupRecorder
import nl.r1a.grassmap.core.services.FavouritesBackupStore
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.SystemClock
import java.io.File

/**
 * The app's handle on the automatic favourites backup (T-0216).
 *
 * The copies live under the app's private files root and hold the exact
 * document [FavouritesAccess.export] produces, so a restore never re-encodes
 * the library. [restore] replaces the whole library file with the chosen copy
 * and drops the cached [FavouritesAccess]; it never merges the copy into the
 * live library. A missing or corrupt copy is a typed error and leaves the live
 * library untouched.
 *
 * The rotation rule and the wall clock are [FavouritesBackupStore]'s; the
 * debounce is [FavouritesBackupRecorder]'s. This class only supplies the
 * favourites export, the library file and the cache invalidation.
 */
class LocalFavouritesBackup(
    root: File?,
    private val favourites: () -> FavouritesAccess,
    scope: CoroutineScope,
    clock: Clock = SystemClock,
    debounceMillis: Long = FavouritesBackupRecorder.DEFAULT_DEBOUNCE_MILLIS,
    private val onRestored: () -> Unit = {},
    onResult: (Outcome<FavouritesBackupCopy>) -> Unit = {},
) {
    private val store: FavouritesBackupStore? =
        root?.let { directory -> FavouritesBackupStore(File(directory, BACKUP_DIRECTORY), clock) }

    private val recorder: FavouritesBackupRecorder? =
        store?.let { backupStore ->
            FavouritesBackupRecorder(
                store = backupStore,
                scope = scope,
                export = { favourites().export() },
                debounceMillis = debounceMillis,
                onResult = onResult,
            )
        }

    private val favouritesFile: File? = root?.let { directory -> File(directory, FAVOURITES_PATH) }

    /** Schedules one debounced copy after a successful mutation. */
    fun onMutation() {
        recorder?.onMutation()
    }

    /** Writes one copy now, outside the debounce. */
    suspend fun backupNow(): Outcome<FavouritesBackupCopy> {
        val backupStore = store ?: return Outcome.Failure(AppError.NotReady)
        return when (val document = favourites().export()) {
            is Outcome.Success -> backupStore.capture(document.value)
            is Outcome.Failure -> document
        }
    }

    /** The retained copies, newest first. */
    suspend fun copies(): Outcome<List<FavouritesBackupCopy>> = store?.list() ?: Outcome.Failure(AppError.NotReady)

    /** The bytes of [id], for a preview or a restore. */
    suspend fun read(id: String): Outcome<ByteArray> = store?.read(id) ?: Outcome.Failure(AppError.NotReady)

    /** Replaces the whole library with [id]; a missing or corrupt copy changes nothing. */
    suspend fun restore(id: String): Outcome<Unit> {
        val backupStore = store ?: return Outcome.Failure(AppError.NotReady)
        val target = favouritesFile ?: return Outcome.Failure(AppError.NotReady)
        return when (val restored = backupStore.restore(id, target)) {
            is Outcome.Failure -> {
                restored
            }

            is Outcome.Success -> {
                onRestored()
                Outcome.Success(Unit)
            }
        }
    }

    private companion object {
        const val BACKUP_DIRECTORY = "backups/favourites"

        // FavouritesAccess keeps this path private; the restore writes the file
        // the access caches, so the path is repeated here and nowhere else.
        const val FAVOURITES_PATH = "favourites/favourites.gpx"
    }
}
