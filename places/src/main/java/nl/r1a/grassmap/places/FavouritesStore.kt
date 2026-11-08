package nl.r1a.grassmap.places

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The persistent favourites library, stored as one OsmAnd favourites GPX file.
 *
 * The file is the same dialect that [FavouritesGpx] imports and exports, so an
 * export is a byte-identical copy of the store and a backup can be imported
 * back. A write goes to a temporary sibling, is flushed and moved over the
 * target atomically, so a failed write leaves the previous file in place. A
 * missing file is an empty store; a corrupt file is [AppError.CorruptData] and
 * is never overwritten by a later mutation.
 *
 * The constructor does no I/O. [load], [save] and every mutation are the
 * lifecycle. Undo is scoped to deletion: [delete] returns a receipt and
 * [undoDelete] consumes it. That is deliberately not a general undo stack,
 * which is T-0304's, and no command in `app` marks a favourites command as
 * undoable.
 */
class FavouritesStore(
    private val file: File,
) {
    private val mutex = Mutex()
    private var cached: FavouritesSnapshot? = null
    private val receipts = LinkedHashMap<FavouriteId, DeletedFavourite>()

    /** Reads the file once; later calls return the cached snapshot. */
    suspend fun load(): Outcome<FavouritesSnapshot> = mutex.withLock { snapshot() }

    /** Replaces the whole library. A failed write leaves the old file intact. */
    suspend fun save(snapshot: FavouritesSnapshot): Outcome<Unit> =
        mutex.withLock {
            when (val written = persist(snapshot)) {
                is Outcome.Failure -> written
                is Outcome.Success -> Outcome.Success(Unit)
            }
        }

    /** Appends a favourite and returns the stored record with its derived id. */
    suspend fun add(favourite: Favourite): Outcome<Favourite> =
        mutex.withLock {
            when (val loaded = snapshot()) {
                is Outcome.Failure -> {
                    loaded
                }

                is Outcome.Success -> {
                    if (!favourite.hasValidValues()) {
                        return@withLock Outcome.Failure(AppError.InvalidInput)
                    }
                    val appended = favourite.copy(id = FavouriteId(EMPTY_ID))
                    when (val written = persist(loaded.value.copy(favourites = loaded.value.favourites + appended))) {
                        is Outcome.Failure -> written
                        is Outcome.Success -> Outcome.Success(written.value.favourites.last())
                    }
                }
            }
        }

    /** Removes a favourite and returns the receipt [undoDelete] needs. */
    suspend fun delete(id: FavouriteId): Outcome<DeletedFavourite> =
        mutex.withLock {
            when (val loaded = snapshot()) {
                is Outcome.Failure -> {
                    loaded
                }

                is Outcome.Success -> {
                    val index = loaded.value.favourites.indexOfFirst { favourite -> favourite.id == id }
                    if (index < 0) {
                        return@withLock Outcome.Failure(AppError.NotFound)
                    }
                    val receipt = DeletedFavourite(loaded.value.favourites[index], index)
                    val remaining =
                        loaded.value.favourites.filterIndexed { position, _ -> position != index }
                    when (val written = persist(loaded.value.copy(favourites = remaining))) {
                        is Outcome.Failure -> {
                            written
                        }

                        is Outcome.Success -> {
                            remember(receipt)
                            Outcome.Success(receipt)
                        }
                    }
                }
            }
        }

    /** Puts a deleted favourite back at the index it occupied. */
    suspend fun undoDelete(id: FavouriteId): Outcome<Favourite> =
        mutex.withLock {
            val receipt = receipts.remove(id) ?: return@withLock Outcome.Failure(AppError.NotFound)
            when (val loaded = snapshot()) {
                is Outcome.Failure -> {
                    remember(receipt)
                    loaded
                }

                is Outcome.Success -> {
                    val index = receipt.index.coerceIn(0, loaded.value.favourites.size)
                    val restored = loaded.value.favourites.toMutableList()
                    restored.add(index, receipt.favourite)
                    when (val written = persist(loaded.value.copy(favourites = restored))) {
                        is Outcome.Failure -> {
                            remember(receipt)
                            written
                        }

                        is Outcome.Success -> {
                            Outcome.Success(written.value.favourites[index])
                        }
                    }
                }
            }
        }

    /** Merges one document into the library, keeping the existing records first. */
    suspend fun import(bytes: ByteArray): Outcome<FavouriteImport> =
        mutex.withLock {
            val document =
                when (val parsed = FavouritesGpx.read(bytes)) {
                    is Outcome.Failure -> return@withLock parsed
                    is Outcome.Success -> parsed.value
                }
            when (val loaded = snapshot()) {
                is Outcome.Failure -> {
                    loaded
                }

                is Outcome.Success -> {
                    val updated =
                        FavouritesSnapshot(
                            groups = mergeGroups(loaded.value.groups, document.snapshot.groups),
                            favourites = loaded.value.favourites + document.snapshot.favourites,
                        )
                    when (val written = persist(updated)) {
                        is Outcome.Failure -> {
                            written
                        }

                        is Outcome.Success -> {
                            Outcome.Success(
                                FavouriteImport(
                                    imported = document.snapshot.favourites.size,
                                    skipped = document.skipped,
                                ),
                            )
                        }
                    }
                }
            }
        }

    /** The deterministic GPX document for the current library. */
    suspend fun export(): Outcome<ByteArray> =
        mutex.withLock {
            when (val loaded = snapshot()) {
                is Outcome.Failure -> loaded
                is Outcome.Success -> Outcome.Success(FavouritesGpx.write(loaded.value))
            }
        }

    private fun snapshot(): Outcome<FavouritesSnapshot> {
        cached?.let { return Outcome.Success(it) }
        if (!file.isFile) {
            val empty = FavouritesSnapshot()
            cached = empty
            return Outcome.Success(empty)
        }
        val bytes =
            try {
                file.readBytes()
            } catch (exception: IOException) {
                return readFailure(exception.message)
            } catch (exception: SecurityException) {
                return readFailure(exception.message)
            }
        return when (val parsed = FavouritesGpx.read(bytes)) {
            is Outcome.Failure -> {
                parsed
            }

            is Outcome.Success -> {
                cached = parsed.value.snapshot
                Outcome.Success(parsed.value.snapshot)
            }
        }
    }

    private fun persist(snapshot: FavouritesSnapshot): Outcome<FavouritesSnapshot> {
        val canonical = canonicalize(snapshot)
        if (!canonical.isValid()) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val parent = file.parentFile ?: return writeFailure(ERROR_ID, "no parent directory")
        return try {
            if (!parent.isDirectory && !parent.mkdirs()) {
                return writeFailure(ERROR_ID, "cannot create $parent")
            }
            val bytes = FavouritesGpx.write(canonical)
            val temporary = File(parent, file.name + TEMPORARY_SUFFIX)
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            cached = canonical
            Outcome.Success(canonical)
        } catch (exception: IOException) {
            writeFailure(ERROR_ID, exception.message)
        } catch (exception: SecurityException) {
            writeFailure(ERROR_ID, exception.message)
        }
    }

    /**
     * A waypoint without its own icon shows the group's icon. The file cannot
     * tell an absent icon from an inherited one, so the store keeps the
     * effective value in the model and never writes the ambiguous one.
     */
    private fun canonicalize(snapshot: FavouritesSnapshot): FavouritesSnapshot {
        val groupIcons = snapshot.groups.associate { it.name to it.icon }
        val resolved =
            snapshot.favourites.map { favourite ->
                if (favourite.icon != null) {
                    favourite
                } else {
                    favourite.copy(icon = favourite.group?.let(groupIcons::get))
                }
            }
        return snapshot.copy(favourites = deriveIds(resolved))
    }

    private fun remember(receipt: DeletedFavourite) {
        receipts[receipt.favourite.id] = receipt
        while (receipts.size > MAX_RECEIPTS) {
            receipts.remove(receipts.keys.first())
        }
    }

    /**
     * Existing definitions win: an import never silently changes how an
     * already-known group looks.
     */
    private fun mergeGroups(
        existing: List<FavouriteGroup>,
        imported: List<FavouriteGroup>,
    ): List<FavouriteGroup> {
        val known = existing.map { it.name }.toSet()
        return existing + imported.filterNot { it.name in known }
    }

    private fun readFailure(detail: String?): Outcome.Failure = Outcome.Failure(AppError.Internal(READ_ERROR_ID, detail))

    private fun writeFailure(
        id: String,
        detail: String?,
    ): Outcome.Failure = Outcome.Failure(AppError.Internal(id, detail))

    private fun FavouritesSnapshot.isValid(): Boolean =
        groups.all { it.isValidRecord() } &&
            groups.map { it.name }.toSet().size == groups.size &&
            favourites.all { it.isValidRecord() } &&
            favourites.map { it.id }.toSet().size == favourites.size

    private fun FavouriteGroup.isValidRecord(): Boolean =
        name.isNotBlank() &&
            name.isPortable() &&
            color.isNotBlank() &&
            color.isPortable() &&
            icon.isPortable()

    private fun Favourite.isValidRecord(): Boolean = id.value.isNotEmpty() && hasValidValues()

    private fun Favourite.hasValidValues(): Boolean =
        latitude.isFinite() &&
            latitude in MIN_LATITUDE..MAX_LATITUDE &&
            longitude.isFinite() &&
            longitude in MIN_LONGITUDE..MAX_LONGITUDE &&
            name.isNotBlank() &&
            name.isPortable() &&
            color.isNotBlank() &&
            color.isPortable() &&
            description.isPortable() &&
            group.isPortable() &&
            icon.isPortable()

    private fun String?.isPortable(): Boolean = this == null || all { character -> character.isPortable() }

    private fun Char.isPortable(): Boolean =
        this == '\t' || this == '\n' || this == '\r' || (this >= ' ' && this != '\uFFFE' && this != '\uFFFF')

    private companion object {
        const val TEMPORARY_SUFFIX = ".tmp"
        const val READ_ERROR_ID = "places.favourites_store_read"
        const val ERROR_ID = "places.favourites_store"
        const val MAX_RECEIPTS = 16
        const val MIN_LATITUDE = -90.0
        const val MAX_LATITUDE = 90.0
        const val MIN_LONGITUDE = -180.0
        const val MAX_LONGITUDE = 180.0
    }
}
