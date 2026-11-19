package nl.r1a.grassmap.core.services.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reads a bundle written by [BundleZipWriter].
 *
 * The manifest decides what is read. Its `formatVersion` above this build's
 * [BACKUP_FORMAT_VERSION] is [AppError.NewerSchema], so an older app refuses a
 * newer bundle whole instead of dropping what it does not understand. A kind
 * this build does not know is skipped with [BackupWarning.UnknownKind] and the
 * known kinds still restore.
 *
 * Every declared file is checked against its byte count and SHA-256 before its
 * kind is read. The reader caps the entries, one entry's uncompressed size and
 * the total, because a ZIP is untrusted input. It extracts nothing to disk, so
 * a path in the archive can neither escape nor overwrite a file.
 */
object BundleZipReader {
    fun read(bytes: ByteArray): Outcome<BackupBundle> = read(ByteArrayInputStream(bytes))

    fun read(input: InputStream): Outcome<BackupBundle> {
        val entries =
            when (val read = readEntries(input)) {
                is Outcome.Success -> read.value
                is Outcome.Failure -> return read
            }
        val manifestBytes = entries[MANIFEST_FILE] ?: return Outcome.Failure(AppError.CorruptData)
        val manifest =
            try {
                Json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)) as? JsonObject
            } catch (exception: SerializationException) {
                null
            } catch (exception: IllegalArgumentException) {
                null
            } ?: return Outcome.Failure(AppError.CorruptData)
        return parseManifest(manifest, entries)
    }

    private fun parseManifest(
        root: JsonObject,
        entries: Map<String, ByteArray>,
    ): Outcome<BackupBundle> {
        if (root.string(FORMAT_KEY) != BACKUP_FORMAT_ID) {
            return Outcome.Failure(AppError.Unsupported)
        }
        val version = root.int(FORMAT_VERSION_KEY) ?: return Outcome.Failure(AppError.CorruptData)
        if (version > BACKUP_FORMAT_VERSION) {
            return Outcome.Failure(AppError.NewerSchema(found = version, supported = BACKUP_FORMAT_VERSION))
        }
        if (version < 1) {
            return Outcome.Failure(AppError.Unsupported)
        }
        val appVersion = root.string(APP_VERSION_KEY) ?: return Outcome.Failure(AppError.CorruptData)
        val createdAt = root.long(CREATED_AT_MILLIS_KEY) ?: return Outcome.Failure(AppError.CorruptData)
        val kinds = root[KINDS_KEY] as? JsonArray ?: return Outcome.Failure(AppError.CorruptData)
        val seenKinds = mutableSetOf<String>()
        val seenPaths = mutableSetOf<String>()
        val parsed = mutableListOf<BundleKind>()
        val warnings = mutableListOf<BackupWarning>()
        for (element in kinds) {
            val kind = element as? JsonObject ?: return Outcome.Failure(AppError.CorruptData)
            val id = kind.string(KIND_KEY) ?: return Outcome.Failure(AppError.CorruptData)
            if (!seenKinds.add(id)) {
                return Outcome.Failure(AppError.CorruptData)
            }
            if (id !in BackupKinds.ALL) {
                warnings += BackupWarning.UnknownKind(id)
                continue
            }
            when (val read = readKind(kind, id, entries, seenPaths)) {
                is Outcome.Success -> parsed += read.value
                is Outcome.Failure -> return read
            }
        }
        return Outcome.Success(
            BackupBundle(
                formatVersion = version,
                appVersion = appVersion,
                createdAtMillis = createdAt,
                kinds = parsed,
                warnings = warnings,
            ),
        )
    }

    private fun readKind(
        kind: JsonObject,
        id: String,
        entries: Map<String, ByteArray>,
        seenPaths: MutableSet<String>,
    ): Outcome<BundleKind> {
        val folder = kind.string(FOLDER_KEY) ?: return Outcome.Failure(AppError.CorruptData)
        if (!isSafeSegment(folder)) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val count = kind.int(COUNT_KEY) ?: return Outcome.Failure(AppError.CorruptData)
        if (count < 0) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val files = kind[FILES_KEY] as? JsonArray ?: return Outcome.Failure(AppError.CorruptData)
        val declared = mutableSetOf<String>()
        for (element in files) {
            val file = element as? JsonObject ?: return Outcome.Failure(AppError.CorruptData)
            val path = file.string(PATH_KEY) ?: return Outcome.Failure(AppError.CorruptData)
            val bytes = file.long(BYTES_KEY) ?: return Outcome.Failure(AppError.CorruptData)
            val hash = file.string(SHA256_KEY) ?: return Outcome.Failure(AppError.CorruptData)
            if (!isPathInside(path, folder) || !declared.add(path) || !seenPaths.add(path)) {
                return Outcome.Failure(AppError.CorruptData)
            }
            val content = entries[path] ?: return Outcome.Failure(AppError.CorruptData)
            if (content.size.toLong() != bytes || sha256Hex(content) != hash) {
                return Outcome.Failure(AppError.CorruptData)
            }
        }
        val index = entries[folder + "/" + BundleZipWriter.INDEX_FILE] ?: return Outcome.Failure(AppError.CorruptData)
        val indexObject =
            try {
                Json.parseToJsonElement(index.toString(Charsets.UTF_8)) as? JsonObject
            } catch (exception: SerializationException) {
                null
            } catch (exception: IllegalArgumentException) {
                null
            } ?: return Outcome.Failure(AppError.CorruptData)
        if (indexObject.string(KIND_KEY) != id) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val list = indexObject[ENTRIES_KEY] as? JsonArray ?: return Outcome.Failure(AppError.CorruptData)
        if (list.size != count) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val ids = mutableSetOf<String>()
        val parsed = mutableListOf<BundleEntry>()
        for (element in list) {
            val objectEntry = element as? JsonObject ?: return Outcome.Failure(AppError.CorruptData)
            val entryId = objectEntry.string(ID_KEY) ?: return Outcome.Failure(AppError.CorruptData)
            if (entryId.isEmpty() || !ids.add(entryId)) {
                return Outcome.Failure(AppError.CorruptData)
            }
            val value = objectEntry[VALUE_KEY] as? JsonObject ?: return Outcome.Failure(AppError.CorruptData)
            val files =
                when (val fileList = objectEntry[FILES_KEY]) {
                    null -> {
                        emptyList()
                    }

                    is JsonArray -> {
                        fileList.map { pathElement ->
                            val path =
                                (pathElement as? JsonPrimitive)?.takeIf { it.isString }?.content
                                    ?: return Outcome.Failure(AppError.CorruptData)
                            if (!isPathInside(path, folder) || path !in declared) {
                                return Outcome.Failure(AppError.CorruptData)
                            }
                            BundleFile(path, entries.getValue(path))
                        }
                    }

                    else -> {
                        return Outcome.Failure(AppError.CorruptData)
                    }
                }
            parsed += BundleEntry(entryId, value, files)
        }
        val kindFiles =
            when (val fileList = indexObject[FILES_KEY]) {
                null -> {
                    emptyList()
                }

                is JsonArray -> {
                    fileList.map { pathElement ->
                        val path =
                            (pathElement as? JsonPrimitive)?.takeIf { it.isString }?.content
                                ?: return Outcome.Failure(AppError.CorruptData)
                        if (!isPathInside(path, folder) || path !in declared) {
                            return Outcome.Failure(AppError.CorruptData)
                        }
                        BundleFile(path, entries.getValue(path))
                    }
                }

                else -> {
                    return Outcome.Failure(AppError.CorruptData)
                }
            }
        return Outcome.Success(BundleKind(id, parsed, kindFiles))
    }

    private fun readEntries(input: InputStream): Outcome<Map<String, ByteArray>> {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory) {
                        continue
                    }
                    if (!isSafePath(name) || entries.size >= MAX_ENTRIES) {
                        return Outcome.Failure(AppError.CorruptData)
                    }
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(READ_CHUNK_BYTES)
                    var size = 0L
                    while (true) {
                        val read = zip.read(chunk)
                        if (read < 0) {
                            break
                        }
                        size += read
                        total += read
                        if (size > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                            return Outcome.Failure(AppError.CorruptData)
                        }
                        buffer.write(chunk, 0, read)
                    }
                    if (entries.put(name, buffer.toByteArray()) != null) {
                        return Outcome.Failure(AppError.CorruptData)
                    }
                    zip.closeEntry()
                }
            }
        } catch (exception: IOException) {
            return Outcome.Failure(AppError.CorruptData)
        } catch (exception: IllegalArgumentException) {
            return Outcome.Failure(AppError.CorruptData)
        }
        return Outcome.Success(entries)
    }

    private fun isSafePath(path: String): Boolean =
        path.isNotEmpty() &&
            !path.startsWith("/") &&
            !path.contains('\\') &&
            path.split('/').none { segment -> segment.isEmpty() || segment == "." || segment == ".." }

    private fun isSafeSegment(value: String): Boolean =
        value.isNotEmpty() &&
            value != "." &&
            value != ".." &&
            value.none { character -> character == '/' || character == '\\' }

    private fun isPathInside(
        path: String,
        folder: String,
    ): Boolean = isSafePath(path) && path.startsWith(folder + "/") && path.length > folder.length + 1

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private const val MANIFEST_FILE = BundleZipWriter.MANIFEST_FILE
    private const val FORMAT_KEY = BundleZipWriter.FORMAT_KEY
    private const val FORMAT_VERSION_KEY = BundleZipWriter.FORMAT_VERSION_KEY
    private const val APP_VERSION_KEY = BundleZipWriter.APP_VERSION_KEY
    private const val CREATED_AT_MILLIS_KEY = BundleZipWriter.CREATED_AT_MILLIS_KEY
    private const val KINDS_KEY = BundleZipWriter.KINDS_KEY
    private const val KIND_KEY = BundleZipWriter.KIND_KEY
    private const val FOLDER_KEY = BundleZipWriter.FOLDER_KEY
    private const val COUNT_KEY = BundleZipWriter.COUNT_KEY
    private const val FILES_KEY = BundleZipWriter.FILES_KEY
    private const val PATH_KEY = BundleZipWriter.PATH_KEY
    private const val BYTES_KEY = BundleZipWriter.BYTES_KEY
    private const val SHA256_KEY = BundleZipWriter.SHA256_KEY
    private const val ENTRIES_KEY = BundleZipWriter.ENTRIES_KEY
    private const val ID_KEY = BundleZipWriter.ID_KEY
    private const val VALUE_KEY = BundleZipWriter.VALUE_KEY

    private const val MAX_ENTRIES = 4096
    private const val MAX_ENTRY_BYTES = 32L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 256L * 1024L * 1024L
    private const val READ_CHUNK_BYTES = 8192
}
