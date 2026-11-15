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
 * the library. [restore] hands the verified copy to the favourites store, which
 * replaces the whole library under its own mutex; it never merges the copy into
 * the live library. A missing or corrupt copy is a typed error and leaves the
 * live library untouched. The store the restore goes through is the one the app
 * already holds, so every reader sees the replaced library without a cache to
 * drop.
 *
 * The rotation rule and the wall clock are [FavouritesBackupStore]'s; the
 * debounce is [FavouritesBackupRecorder]'s. This class only supplies the
 * favourites export and the verified copy a restore needs.
 */
class LocalFavouritesBackup(
    root: File?,
    private val favourites: () -> FavouritesAccess,
    scope: CoroutineScope,
    clock: Clock = SystemClock,
    debounceMillis: Long = FavouritesBackupRecorder.DEFAULT_DEBOUNCE_MILLIS,
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
        return when (val stored = backupStore.read(id)) {
            is Outcome.Failure -> stored
            is Outcome.Success -> favourites().restore(stored.value)
        }
    }

    private companion object {
        const val BACKUP_DIRECTORY = "backups/favourites"
    }
}
