package nl.r1a.grassmap.plugins.track.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

/** One folder the library screen draws. [id] survives a rename. */
@Serializable
data class TrackFolder(
    val id: String,
    val name: String,
)

/**
 * The folder definitions of the track library (T-0414), stored as one small
 * JSON index beside the track documents. The per-track folder id lives on the
 * track sidecar; this file only names the folders.
 *
 * A missing index is an empty library, never an exception, so one bad file
 * cannot hide the tracks. An index this build cannot read - damaged bytes, a
 * schema from a newer build, or a file the process cannot open - is also read
 * as an empty library, but every change that would rewrite it is refused
 * ([AppError.CorruptData], [AppError.Unsupported] or [AppError.Internal]), so
 * the file on disk survives for the build that can use it. A null [file] keeps
 * the folders in memory, which is what the unit tests and a build without a
 * files root use.
 *
 * Folder names are trimmed, non-blank, at most [MAX_NAME_LENGTH] characters and
 * unique without regard to case. A name that breaks a rule is
 * [AppError.InvalidInput] and changes nothing.
 */
class TrackFolderStore(
    private val file: File?,
    private val idSource: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private var loaded = false

    /** Why the index cannot be rewritten, or null when it can. */
    private var indexFailure: AppError? = null
    private val folders = mutableListOf<TrackFolder>()

    suspend fun load(): List<TrackFolder> =
        mutex.withLock {
            withContext(Dispatchers.IO) { ensureLoaded() }
            folders.toList()
        }

    suspend fun find(id: String): TrackFolder? =
        mutex.withLock {
            withContext(Dispatchers.IO) { ensureLoaded() }
            folders.firstOrNull { folder -> folder.id == id }
        }

    suspend fun create(name: String): Outcome<TrackFolder> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                ensureLoaded()
                rewriteRefusal()?.let { refusal -> return@withContext refusal }
                val cleaned = validName(name, exceptId = null) ?: return@withContext Outcome.Failure(AppError.InvalidInput)
                val folder = TrackFolder(id = idSource(), name = cleaned)
                folders += folder
                if (!persist()) {
                    folders.remove(folder)
                    return@withContext Outcome.Failure(AppError.Internal(ERROR_ID, "cannot write the folder index"))
                }
                Outcome.Success(folder)
            }
        }

    suspend fun rename(
        id: String,
        name: String,
    ): Outcome<TrackFolder> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                ensureLoaded()
                rewriteRefusal()?.let { refusal -> return@withContext refusal }
                val index = folders.indexOfFirst { folder -> folder.id == id }
                if (index < 0) {
                    return@withContext Outcome.Failure(AppError.NotFound)
                }
                val cleaned = validName(name, exceptId = id) ?: return@withContext Outcome.Failure(AppError.InvalidInput)
                val previous = folders[index]
                val renamed = previous.copy(name = cleaned)
                folders[index] = renamed
                if (!persist()) {
                    folders[index] = previous
                    return@withContext Outcome.Failure(AppError.Internal(ERROR_ID, "cannot write the folder index"))
                }
                Outcome.Success(renamed)
            }
        }

    /** Removes one folder; the tracks that named it become unfiled at the access layer. */
    suspend fun remove(id: String): Outcome<Unit> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                ensureLoaded()
                rewriteRefusal()?.let { refusal -> return@withContext refusal }
                val index = folders.indexOfFirst { folder -> folder.id == id }
                if (index < 0) {
                    return@withContext Outcome.Failure(AppError.NotFound)
                }
                val removed = folders.removeAt(index)
                if (!persist()) {
                    folders.add(index, removed)
                    return@withContext Outcome.Failure(AppError.Internal(ERROR_ID, "cannot write the folder index"))
                }
                Outcome.Success(Unit)
            }
        }

    /**
     * Puts a folder back at [index], used by the create undo. The caller has
     * already checked that its name does not collide.
     */
    suspend fun restore(
        folder: TrackFolder,
        index: Int,
    ): Outcome<Unit> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                ensureLoaded()
                rewriteRefusal()?.let { refusal -> return@withContext refusal }
                if (folders.any { existing -> existing.id == folder.id }) {
                    return@withContext Outcome.Failure(AppError.InvalidInput)
                }
                if (validName(folder.name, exceptId = null) != folder.name) {
                    return@withContext Outcome.Failure(AppError.InvalidInput)
                }
                folders.add(index.coerceIn(0, folders.size), folder)
                if (!persist()) {
                    folders.remove(folder)
                    return@withContext Outcome.Failure(AppError.Internal(ERROR_ID, "cannot write the folder index"))
                }
                Outcome.Success(Unit)
            }
        }

    /**
     * The refusal a change answers when the index on disk is not one this build
     * can rewrite. Reading stays tolerant; writing must not replace a file that
     * was not read.
     */
    private fun rewriteRefusal(): Outcome.Failure? = indexFailure?.let { failure -> Outcome.Failure(failure) }

    private fun ensureLoaded() {
        if (loaded) {
            return
        }
        folders.clear()
        indexFailure = null
        val target = file
        if (target != null && target.isFile) {
            val text =
                try {
                    target.readText()
                } catch (exception: IOException) {
                    null
                } catch (exception: SecurityException) {
                    null
                }
            if (text == null) {
                indexFailure = AppError.Internal(ERROR_ID, "the folder index could not be read")
            } else {
                when (val decoded = decode(text)) {
                    is Outcome.Success -> folders += decoded.value
                    is Outcome.Failure -> indexFailure = decoded.error
                }
            }
        }
        loaded = true
    }

    /** The trimmed name, or null when it is blank, too long or already used. */
    private fun validName(
        name: String,
        exceptId: String?,
    ): String? {
        val cleaned = name.trim()
        if (cleaned.isEmpty() || cleaned.length > MAX_NAME_LENGTH) {
            return null
        }
        val key = cleaned.lowercase(Locale.ROOT)
        val collision = folders.any { folder -> folder.id != exceptId && folder.name.lowercase(Locale.ROOT) == key }
        return if (collision) null else cleaned
    }

    /**
     * The folders [text] names, or why this build must not rewrite it. A schema
     * this build does not know is [AppError.Unsupported]: a newer build's folder
     * names must not be replaced by this one's empty list.
     */
    private fun decode(text: String): Outcome<List<TrackFolder>> =
        try {
            val index = INDEX_JSON.decodeFromString<FolderIndex>(text)
            if (index.schema != SCHEMA_VERSION) {
                Outcome.Failure(AppError.Unsupported)
            } else {
                Outcome.Success(
                    index.folders.filter { folder ->
                        folder.id.isNotBlank() && folder.name.isNotBlank() && folder.name.length <= MAX_NAME_LENGTH
                    },
                )
            }
        } catch (exception: SerializationException) {
            Outcome.Failure(AppError.CorruptData)
        } catch (exception: IllegalArgumentException) {
            Outcome.Failure(AppError.CorruptData)
        }

    private fun encode(): ByteArray =
        INDEX_JSON.encodeToString(FolderIndex(schema = SCHEMA_VERSION, folders = folders.toList())).toByteArray()

    private fun persist(): Boolean {
        val target = file ?: return true
        val parent = target.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) {
            return false
        }
        val temporary = File(parent, TEMPORARY_PREFIX + target.name + TEMPORARY_SUFFIX)
        return try {
            FileOutputStream(temporary).use { stream ->
                stream.write(encode())
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (exception: IOException) {
            temporary.delete()
            false
        } catch (exception: SecurityException) {
            temporary.delete()
            false
        }
    }

    /** The one file the index is written to. */
    @Serializable
    private data class FolderIndex(
        val schema: Int,
        val folders: List<TrackFolder> = emptyList(),
    )

    companion object {
        const val MAX_NAME_LENGTH: Int = 80

        /**
         * A newer build may add a field to the index. An unknown field is
         * ignored, so it cannot turn the folder names into an empty list; a
         * schema this build does not know is refused instead of guessed.
         */
        private val INDEX_JSON: Json = Json { ignoreUnknownKeys = true }

        private const val SCHEMA_VERSION = 1
        private const val TEMPORARY_PREFIX = "."
        private const val TEMPORARY_SUFFIX = ".part"
        private const val ERROR_ID = "track.library.folders"
    }
}
