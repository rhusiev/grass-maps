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
 * lifecycle. Undo is scoped per action: [delete] returns a receipt that
 * [undoDelete] consumes, and the lists actions return [FavouriteMutation.undo]
 * tokens that [undoListAction] consumes. That is deliberately not a general
 * undo stack, which is T-0304's, and no command in `app` marks a favourites
 * command as undoable.
 */
class FavouritesStore(
    private val file: File,
) {
    private val mutex = Mutex()
    private var cached: FavouritesSnapshot? = null
    private val receipts = LinkedHashMap<FavouriteId, DeletedFavourite>()
    private val listReceipts = LinkedHashMap<String, ListReceipt>()
    private var listSequence = 0

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

    /**
     * Creates one list (group) with its colour and optional icon (T-0213).
     * A blank name or colour, or a duplicate name, is [AppError.InvalidInput].
     *
     * The returned [FavouriteMutation.undo] receipt removes the list again. It
     * is scoped to this action and is not the T-0304 undo history.
     */
    suspend fun createGroup(
        name: String,
        color: String,
        icon: String? = null,
    ): Outcome<FavouriteMutation<FavouriteGroup>> =
        mutex.withLock {
            val cleanName = name.trim()
            val cleanColor = color.trim()
            val cleanIcon = icon?.trim()?.takeIf { it.isNotEmpty() }
            if (cleanName.isEmpty() || cleanColor.isEmpty()) {
                return@withLock Outcome.Failure(AppError.InvalidInput)
            }
            when (val loaded = snapshot()) {
                is Outcome.Failure -> {
                    loaded
                }

                is Outcome.Success -> {
                    if (loaded.value.groups.any { group -> group.name == cleanName }) {
                        return@withLock Outcome.Failure(AppError.InvalidInput)
                    }
                    val group = FavouriteGroup(name = cleanName, color = cleanColor, icon = cleanIcon)
                    val updated = loaded.value.copy(groups = loaded.value.groups + group)
                    when (val written = persist(updated)) {
                        is Outcome.Failure -> {
                            written
                        }

                        is Outcome.Success -> {
                            val index = written.value.groups.lastIndex
                            Outcome.Success(
                                FavouriteMutation(
                                    written.value.groups[index],
                                    rememberList(ListReceipt.Created(group)),
                                ),
                            )
                        }
                    }
                }
            }
        }

    /**
     * Moves one favourite to another list and persists it. A point whose
     * appearance matched its old list inherits the destination's appearance;
     * a point with its own look keeps it. A missing point or list is
     * [AppError.NotFound].
     */
    suspend fun moveFavourite(
        id: FavouriteId,
        group: String,
    ): Outcome<FavouriteMutation<Favourite>> =
        mutex.withLock {
            val target = group.trim()
            if (target.isEmpty()) {
                return@withLock Outcome.Failure(AppError.InvalidInput)
            }
            when (val loaded = snapshot()) {
                is Outcome.Failure -> {
                    loaded
                }

                is Outcome.Success -> {
                    val snapshot = loaded.value
                    val index = snapshot.favourites.indexOfFirst { favourite -> favourite.id == id }
                    if (index < 0) {
                        return@withLock Outcome.Failure(AppError.NotFound)
                    }
                    val destination =
                        snapshot.groups.firstOrNull { it.name == target }
                            ?: return@withLock Outcome.Failure(AppError.NotFound)
                    val before = snapshot.favourites[index]
                    val source = before.group?.let { name -> snapshot.groups.firstOrNull { it.name == name } }
                    val inherited =
                        source != null &&
                            before.color == source.color &&
                            before.icon == source.icon &&
                            before.shape == source.shape
                    val after =
                        if (inherited) {
                            before.copy(
                                group = target,
                                color = destination.color,
                                icon = destination.icon,
                                shape = destination.shape,
                            )
                        } else {
                            before.copy(group = target)
                        }
                    val favourites = snapshot.favourites.toMutableList().also { it[index] = after }
                    when (val written = persist(snapshot.copy(favourites = favourites))) {
                        is Outcome.Failure -> {
                            written
                        }

                        is Outcome.Success -> {
                            Outcome.Success(
                                FavouriteMutation(
                                    written.value.favourites[index],
                                    rememberList(
                                        ListReceipt.Moved(
                                            before = before,
                                            after = written.value.favourites[index],
                                            sourceGroup = source,
                                            inherited = inherited,
                                        ),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }

    /**
     * Recolours a whole list and every point in it (T-0213). The group default
     * and every member's effective colour get [color], whether the point
     * inherited the list's look or carried its own. Other lists and points are
     * untouched. An unknown list is [AppError.NotFound].
     */
    suspend fun recolourGroup(
        name: String,
        color: String,
    ): Outcome<FavouriteMutation<FavouriteGroup>> =
        mutex.withLock {
            val target = name.trim()
            val cleanColor = color.trim()
            if (target.isEmpty() || cleanColor.isEmpty()) {
                return@withLock Outcome.Failure(AppError.InvalidInput)
            }
            when (val loaded = snapshot()) {
                is Outcome.Failure -> {
                    loaded
                }

                is Outcome.Success -> {
                    val snapshot = loaded.value
                    val index = snapshot.groups.indexOfFirst { group -> group.name == target }
                    if (index < 0) {
                        return@withLock Outcome.Failure(AppError.NotFound)
                    }
                    val before = snapshot.groups[index]
                    val members =
                        snapshot.favourites.mapNotNull { favourite ->
                            if (favourite.group == target) {
                                MemberColor(favourite.name, favourite.latitude, favourite.longitude, favourite.color)
                            } else {
                                null
                            }
                        }
                    val favourites =
                        snapshot.favourites.map { favourite ->
                            if (favourite.group == target) favourite.copy(color = cleanColor) else favourite
                        }
                    val groups = snapshot.groups.toMutableList().also { it[index] = before.copy(color = cleanColor) }
                    when (val written = persist(snapshot.copy(groups = groups, favourites = favourites))) {
                        is Outcome.Failure -> {
                            written
                        }

                        is Outcome.Success -> {
                            Outcome.Success(
                                FavouriteMutation(
                                    written.value.groups[index],
                                    rememberList(ListReceipt.Recoloured(target, before.color, cleanColor, members)),
                                ),
                            )
                        }
                    }
                }
            }
        }

    /**
     * Sets one favourite as the Home or Work special place (T-0213). The
     * previous holder of that special, if any, is cleared, so a library holds
     * exactly one Home and one Work at most. A missing point is
     * [AppError.NotFound].
     */
    suspend fun setSpecial(
        id: FavouriteId,
        special: FavouriteSpecial,
    ): Outcome<FavouriteMutation<Favourite>> =
        mutex.withLock {
            when (val loaded = snapshot()) {
                is Outcome.Failure -> {
                    loaded
                }

                is Outcome.Success -> {
                    val snapshot = loaded.value
                    val index = snapshot.favourites.indexOfFirst { favourite -> favourite.id == id }
                    if (index < 0) {
                        return@withLock Outcome.Failure(AppError.NotFound)
                    }
                    val before = snapshot.favourites[index]
                    val holder = snapshot.favourites.firstOrNull { it.id != id && it.special == special }
                    val favourites =
                        snapshot.favourites.map { favourite ->
                            when {
                                favourite.id == id -> favourite.copy(special = special)
                                favourite.id == holder?.id -> favourite.copy(special = null)
                                else -> favourite
                            }
                        }
                    when (val written = persist(snapshot.copy(favourites = favourites))) {
                        is Outcome.Failure -> {
                            written
                        }

                        is Outcome.Success -> {
                            Outcome.Success(
                                FavouriteMutation(
                                    written.value.favourites[index],
                                    rememberList(
                                        ListReceipt.SpecialSet(
                                            target = id,
                                            before = before.special,
                                            after = special,
                                            holder = holder?.id,
                                        ),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }

    /**
     * Consumes one lists receipt. When the action can no longer apply - the
     * point or list was deleted, or another action changed it - the result is
     * [FavouriteUndo.NOT_APPLICABLE] and the library is unchanged. That is a
     * typed no-op, not an error.
     *
     * The receipt is consumed on success and on [FavouriteUndo.NOT_APPLICABLE].
     * A write failure keeps it under the caller's own token, so the same token
     * can retry once the failure is gone.
     */
    suspend fun undoListAction(token: FavouriteUndoToken): Outcome<FavouriteUndo> =
        mutex.withLock {
            val receipt = listReceipts.remove(token.value) ?: return@withLock Outcome.Success(FavouriteUndo.NOT_APPLICABLE)
            when (val loaded = snapshot()) {
                is Outcome.Failure -> {
                    rememberList(receipt, token.value)
                    loaded
                }

                is Outcome.Success -> {
                    val updated = applyUndo(loaded.value, receipt)
                    if (updated == null) {
                        return@withLock Outcome.Success(FavouriteUndo.NOT_APPLICABLE)
                    }
                    when (val written = persist(updated)) {
                        is Outcome.Failure -> {
                            rememberList(receipt, token.value)
                            written
                        }

                        is Outcome.Success -> {
                            Outcome.Success(FavouriteUndo.APPLIED)
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
        val seen = mutableSetOf<FavouriteSpecial>()
        val unique =
            resolved.map { favourite ->
                val special = favourite.special
                if (special == null || seen.add(special)) favourite else favourite.copy(special = null)
            }
        return snapshot.copy(favourites = deriveIds(unique))
    }

    private fun remember(receipt: DeletedFavourite) {
        receipts[receipt.favourite.id] = receipt
        while (receipts.size > MAX_RECEIPTS) {
            receipts.remove(receipts.keys.first())
        }
    }

    /**
     * Remembers a receipt and returns its token. [key] re-uses the caller's
     * original token after a failed write, so a retry cannot strand the action.
     */
    private fun rememberList(
        receipt: ListReceipt,
        key: String? = null,
    ): FavouriteUndoToken {
        val token =
            if (key != null) {
                FavouriteUndoToken(key)
            } else {
                listSequence += 1
                FavouriteUndoToken("lists-$listSequence")
            }
        listReceipts[token.value] = receipt
        while (listReceipts.size > MAX_RECEIPTS) {
            listReceipts.remove(listReceipts.keys.first())
        }
        return token
    }

    /** The snapshot with one lists action reversed, or null when it cannot apply. */
    private fun applyUndo(
        snapshot: FavouritesSnapshot,
        receipt: ListReceipt,
    ): FavouritesSnapshot? =
        when (receipt) {
            is ListReceipt.Created -> {
                if (snapshot.groups.none { it.name == receipt.group.name }) {
                    null
                } else if (snapshot.favourites.any { it.group == receipt.group.name }) {
                    // The list has members, so removing it would silently
                    // ungroup points this receipt never moved.
                    null
                } else {
                    snapshot.copy(groups = snapshot.groups.filterNot { it.name == receipt.group.name })
                }
            }

            is ListReceipt.Moved -> {
                val index = snapshot.favourites.indexOfFirst { sameValues(it, receipt.after) }
                if (index < 0) {
                    null
                } else {
                    val source =
                        receipt.before.group?.let { name -> snapshot.groups.firstOrNull { it.name == name } }
                            ?: receipt.sourceGroup
                    val restored =
                        if (receipt.inherited && source != null) {
                            receipt.before.copy(color = source.color, icon = source.icon, shape = source.shape)
                        } else {
                            receipt.before
                        }
                    snapshot.copy(favourites = snapshot.favourites.toMutableList().also { it[index] = restored })
                }
            }

            is ListReceipt.Recoloured -> {
                val group = snapshot.groups.firstOrNull { it.name == receipt.group }
                if (group == null || group.color != receipt.afterColor) {
                    null
                } else {
                    val favourites =
                        snapshot.favourites.map { favourite ->
                            if (favourite.group != receipt.group || favourite.color != receipt.afterColor) {
                                favourite
                            } else {
                                val member =
                                    receipt.members.firstOrNull { member ->
                                        member.name == favourite.name &&
                                            member.latitude == favourite.latitude &&
                                            member.longitude == favourite.longitude
                                    }
                                if (member == null) favourite else favourite.copy(color = member.color)
                            }
                        }
                    snapshot.copy(
                        groups =
                            snapshot.groups.map { existing ->
                                if (existing.name == receipt.group) existing.copy(color = receipt.beforeColor) else existing
                            },
                        favourites = favourites,
                    )
                }
            }

            is ListReceipt.SpecialSet -> {
                applySpecialUndo(snapshot, receipt)
            }
        }

    private fun applySpecialUndo(
        snapshot: FavouritesSnapshot,
        receipt: ListReceipt.SpecialSet,
    ): FavouritesSnapshot? {
        val target = snapshot.favourites.firstOrNull { it.id == receipt.target } ?: return null
        if (target.special != receipt.after) {
            return null
        }
        val holder = receipt.holder?.let { id -> snapshot.favourites.firstOrNull { it.id == id } }
        if (receipt.holder != null && holder == null) {
            // The previous holder is gone. Restoring only the target would
            // report APPLIED while Home or Work is lost, so this cannot apply.
            return null
        }
        if (receipt.before != null && snapshot.favourites.any { it.id != target.id && it.special == receipt.before }) {
            return null
        }
        if (holder != null && holder.special != null && holder.special != receipt.after) {
            return null
        }
        val restored = target.copy(special = receipt.before)
        val favourites =
            snapshot.favourites.map { favourite ->
                when {
                    favourite.id == target.id -> restored
                    holder != null && favourite.id == holder.id -> favourite.copy(special = receipt.after)
                    else -> favourite
                }
            }
        return snapshot.copy(favourites = favourites)
    }

    /** Equality of every stored value except the derived id. */
    private fun sameValues(
        first: Favourite,
        second: Favourite,
    ): Boolean =
        first.latitude == second.latitude &&
            first.longitude == second.longitude &&
            first.name == second.name &&
            first.description == second.description &&
            first.group == second.group &&
            first.color == second.color &&
            first.icon == second.icon &&
            first.shape == second.shape &&
            first.special == second.special

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

    private sealed interface ListReceipt {
        data class Created(
            val group: FavouriteGroup,
        ) : ListReceipt

        data class Moved(
            val before: Favourite,
            val after: Favourite,
            val sourceGroup: FavouriteGroup?,
            val inherited: Boolean,
        ) : ListReceipt

        data class Recoloured(
            val group: String,
            val beforeColor: String,
            val afterColor: String,
            val members: List<MemberColor>,
        ) : ListReceipt

        data class SpecialSet(
            val target: FavouriteId,
            val before: FavouriteSpecial?,
            val after: FavouriteSpecial,
            val holder: FavouriteId?,
        ) : ListReceipt
    }

    /** One recoloured point, matched by its stable GPX values across a reorder. */
    private data class MemberColor(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val color: String,
    )

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
