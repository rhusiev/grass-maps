package nl.r1a.grassmap.core.services

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.SystemClock
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** One retained favourites backup and the facts needed to verify it. */
data class FavouritesBackupCopy(
    /** The file name without its extension; also the value a restore takes. */
    val id: String,
    /** When the copy was taken, in epoch milliseconds on the injected clock. */
    val takenAtMillis: Long,
    val sizeBytes: Long,
    /** The SHA-256 of the stored document, lowercase hex. */
    val digest: String,
)

/**
 * The automatic local backup of the favourites library (T-0216).
 *
 * A copy holds the exact bytes that `FavouritesAccess.export()` produced, so a
 * restore writes the document back without re-encoding it, and the favourites
 * store and the GPX codec are not duplicated here. The store never parses the
 * document: it keeps bytes and detects damage with the SHA-256 recorded in the
 * index.
 *
 * Rotation keeps the newest [maxCopies] copies by their recorded time, never
 * more than [maxCopiesPerDay] new copies for one UTC calendar day on the
 * injected [Clock]. A same-day burst coalesces into the day's second copy: the
 * first two captures of a day create copies, and every later capture replaces
 * the day's newest copy with a newer one, so a day never holds more than two.
 * Replacement writes the new copy first and removes the replaced one after the
 * index is durable; an interrupted replacement leaves the replaced copy in
 * place.
 *
 * Ordering comes from the index, never from the file modification time. The
 * file name records what the copy is (`favourites-<epochMillis>-<sequence>`)
 * and the index adds its size and digest. A missing or unreadable index is
 * rebuilt from the copy files, so losing the index does not lose the copies.
 *
 * Every write goes to a temporary sibling, is flushed and moved atomically, so
 * a failed write leaves the previous copies intact. A failed capture also
 * removes the copy it just wrote. The constructor does no I/O; [capture],
 * [list], [read] and [restore] are the lifecycle.
 */
class FavouritesBackupStore(
    private val directory: File,
    private val clock: Clock = SystemClock,
    private val maxCopies: Int = DEFAULT_MAX_COPIES,
    private val maxCopiesPerDay: Int = DEFAULT_MAX_COPIES_PER_DAY,
) {
    private val mutex = Mutex()

    init {
        require(maxCopies > 0) { "maxCopies must be positive" }
        require(maxCopiesPerDay > 0) { "maxCopiesPerDay must be positive" }
    }

    /**
     * Stores [document] under the current rotation rule and returns the copy.
     *
     * A burst after the day's second copy replaces that day's newest copy. The
     * whole capture either lands or leaves the previous copies as they were.
     */
    suspend fun capture(document: ByteArray): Outcome<FavouritesBackupCopy> = mutex.withLock { captureLocked(document) }

    /** The retained copies, newest first. */
    suspend fun list(): Outcome<List<FavouritesBackupCopy>> =
        mutex.withLock {
            Outcome.Success(loadEntries().sortedWith(NEWEST_FIRST))
        }

    /**
     * The bytes of [id]. A copy that is not in the index is [AppError.NotFound];
     * a present copy whose size or digest does not match the index is
     * [AppError.CorruptData].
     */
    suspend fun read(id: String): Outcome<ByteArray> = mutex.withLock { readLocked(id) }

    /**
     * Replaces [target] with the bytes of [id], atomically.
     *
     * The copy is verified before [target] is touched, so a missing or corrupt
     * copy leaves the live file unchanged. A failed write is a typed error and
     * also leaves the live file unchanged.
     */
    suspend fun restore(
        id: String,
        target: File,
    ): Outcome<Unit> =
        mutex.withLock {
            when (val stored = readLocked(id)) {
                is Outcome.Failure -> {
                    stored
                }

                is Outcome.Success -> {
                    AtomicFiles.write(target, ERROR_ID) { stream ->
                        stream.write(stored.value)
                    }
                }
            }
        }

    private fun captureLocked(document: ByteArray): Outcome<FavouritesBackupCopy> {
        if (document.isEmpty()) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val entries = loadEntries()
        val now = clock.nowMillis()
        val copy = newCopy(now, document, entries)
        val kept = retained(entries, copy, now)
        val dropped = entries.filterNot { entry -> kept.any { it.id == entry.id } }
        val written =
            AtomicFiles.write(fileFor(copy.id), ERROR_ID) { stream ->
                stream.write(document)
            }
        if (written is Outcome.Failure) {
            return written
        }
        val indexed = writeIndex(kept)
        if (indexed is Outcome.Failure) {
            // The copy is not referenced by the index, so it must not linger.
            fileFor(copy.id).delete()
            return indexed
        }
        dropped.forEach { entry -> fileFor(entry.id).delete() }
        return Outcome.Success(copy)
    }

    private fun readLocked(id: String): Outcome<ByteArray> {
        val entry = loadEntries().firstOrNull { it.id == id } ?: return Outcome.Failure(AppError.NotFound)
        val file = fileFor(entry.id)
        if (!file.isFile) {
            return Outcome.Failure(AppError.NotFound)
        }
        val bytes =
            try {
                file.readBytes()
            } catch (exception: IOException) {
                return Outcome.Failure(AppError.CorruptData)
            } catch (exception: SecurityException) {
                return Outcome.Failure(AppError.CorruptData)
            }
        if (bytes.size.toLong() != entry.sizeBytes || digestOf(bytes) != entry.digest) {
            return Outcome.Failure(AppError.CorruptData)
        }
        return Outcome.Success(bytes)
    }

    /**
     * The entries to keep after adding [copy]: the day's two-copy ceiling first,
     * then the total ceiling. The replaced copy and the pruned older ones are
     * deleted only after the index is durable.
     */
    private fun retained(
        entries: List<FavouritesBackupCopy>,
        copy: FavouritesBackupCopy,
        nowMillis: Long,
    ): List<FavouritesBackupCopy> {
        val today = dayOf(nowMillis)
        val sameDay = entries.filter { dayOf(it.takenAtMillis) == today }
        val base =
            if (sameDay.size >= maxCopiesPerDay) {
                val replaced = sameDay.maxWith(OLDEST_FIRST)
                entries.filterNot { it.id == replaced.id }
            } else {
                entries
            }
        return (base + copy).sortedWith(OLDEST_FIRST).takeLast(maxCopies)
    }

    private fun newCopy(
        nowMillis: Long,
        document: ByteArray,
        entries: List<FavouritesBackupCopy>,
    ): FavouritesBackupCopy {
        val sequence = entries.mapNotNull { entry -> sequenceOf(entry.id) }.maxOrNull()?.plus(1) ?: 1L
        return FavouritesBackupCopy(
            id = idOf(nowMillis, sequence),
            takenAtMillis = nowMillis,
            sizeBytes = document.size.toLong(),
            digest = digestOf(document),
        )
    }

    private fun loadEntries(): List<FavouritesBackupCopy> {
        if (!indexFile.isFile) {
            return rebuildEntries()
        }
        val text =
            try {
                indexFile.readText()
            } catch (exception: IOException) {
                return rebuildEntries()
            } catch (exception: SecurityException) {
                return rebuildEntries()
            }
        val parsed = parseIndex(text) ?: return rebuildEntries()
        return parsed.filter { entry -> fileFor(entry.id).isFile }
    }

    private fun parseIndex(text: String): List<FavouritesBackupCopy>? {
        val lines = text.lineSequence().filter { line -> line.isNotEmpty() }.toList()
        if (lines.firstOrNull() != INDEX_HEADER) {
            return null
        }
        val entries = mutableListOf<FavouritesBackupCopy>()
        for (line in lines.drop(1)) {
            val fields = line.split('\t')
            if (fields.size != INDEX_FIELD_COUNT) {
                return null
            }
            val id = fields[0]
            val takenAtMillis = fields[1].toLongOrNull() ?: return null
            val sizeBytes = fields[2].toLongOrNull() ?: return null
            val digest = fields[3]
            if (!ID_PATTERN.matches(id) || sizeBytes < 0 || !isDigest(digest)) {
                return null
            }
            if (entries.any { entry -> entry.id == id }) {
                return null
            }
            entries += FavouritesBackupCopy(id, takenAtMillis, sizeBytes, digest)
        }
        return entries
    }

    /** Rebuilds the index from the copy files, so a lost index keeps the copies. */
    private fun rebuildEntries(): List<FavouritesBackupCopy> =
        (directory.listFiles() ?: emptyArray())
            .filter { file -> file.isFile && ID_PATTERN.matches(file.nameWithoutExtension) }
            .mapNotNull { file ->
                val id = file.nameWithoutExtension
                val millis = millisOf(id) ?: return@mapNotNull null
                val bytes =
                    try {
                        file.readBytes()
                    } catch (exception: IOException) {
                        null
                    } catch (exception: SecurityException) {
                        null
                    } ?: return@mapNotNull null
                FavouritesBackupCopy(id, millis, bytes.size.toLong(), digestOf(bytes))
            }.sortedWith(OLDEST_FIRST)

    private fun writeIndex(entries: List<FavouritesBackupCopy>): Outcome<Unit> =
        AtomicFiles.write(indexFile, ERROR_ID) { stream ->
            val text =
                buildString {
                    append(INDEX_HEADER).append('\n')
                    entries.sortedWith(OLDEST_FIRST).forEach { entry ->
                        append(entry.id)
                            .append('\t')
                            .append(entry.takenAtMillis)
                            .append('\t')
                            .append(entry.sizeBytes)
                            .append('\t')
                            .append(entry.digest)
                            .append('\n')
                    }
                }
            stream.write(text.toByteArray(Charsets.UTF_8))
        }

    private fun fileFor(id: String): File = File(directory, "$id.gpx")

    private val indexFile: File
        get() = File(directory, INDEX_FILE_NAME)

    companion object {
        /** The number of copies the rotation keeps. */
        const val DEFAULT_MAX_COPIES = 10

        /** The number of new copies one UTC calendar day may add. */
        const val DEFAULT_MAX_COPIES_PER_DAY = 2

        private const val ERROR_ID = "core.services.favourites_backup"
        private const val INDEX_FILE_NAME = "index.tsv"
        private const val INDEX_HEADER = "grass-maps-favourites-backups\t1"
        private const val INDEX_FIELD_COUNT = 4
        private const val ID_PREFIX = "favourites-"
        private const val DIGEST_ALGORITHM = "SHA-256"
        private const val DIGEST_LENGTH = 64
        private const val BYTE_MASK = 0xFF
        private const val MILLIS_GROUP = 1
        private const val SEQUENCE_GROUP = 2

        private val ID_PATTERN = Regex("^favourites-(\\d+)-(\\d+)$")

        private val OLDEST_FIRST: Comparator<FavouritesBackupCopy> =
            compareBy<FavouritesBackupCopy> { copy -> copy.takenAtMillis }.thenBy { copy -> copy.id }

        private val NEWEST_FIRST: Comparator<FavouritesBackupCopy> = OLDEST_FIRST.reversed()

        fun idOf(
            millis: Long,
            sequence: Long,
        ): String = String.format(Locale.ROOT, "%s%d-%04d", ID_PREFIX, millis, sequence)

        fun millisOf(id: String): Long? =
            ID_PATTERN
                .find(id)
                ?.groupValues
                ?.getOrNull(MILLIS_GROUP)
                ?.toLongOrNull()

        fun sequenceOf(id: String): Long? =
            ID_PATTERN
                .find(id)
                ?.groupValues
                ?.getOrNull(SEQUENCE_GROUP)
                ?.toLongOrNull()

        fun isDigest(digest: String): Boolean =
            digest.length == DIGEST_LENGTH &&
                digest.all { character ->
                    character in '0'..'9' || character in 'a'..'f'
                }

        fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

        fun digestOf(bytes: ByteArray): String =
            MessageDigest
                .getInstance(DIGEST_ALGORITHM)
                .digest(bytes)
                .joinToString(separator = "") { byte ->
                    String.format(Locale.ROOT, "%02x", byte.toInt() and BYTE_MASK)
                }
    }
}
