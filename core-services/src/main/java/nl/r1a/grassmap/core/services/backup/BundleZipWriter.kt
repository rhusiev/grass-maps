package nl.r1a.grassmap.core.services.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The SHA-256 the manifest and the readers use, as lowercase hex. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance(SHA_256)
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }

private const val SHA_256 = "SHA-256"
private const val BYTE_MASK = 0xff

/** The manifest's file record. */
internal data class ManifestFile(
    val path: String,
    val bytes: Long,
    val sha256: String,
)

/** Writes one bundle: a manifest, one folder per kind and one index per folder. */
object BundleZipWriter {
    /**
     * Writes [bundle] to [output]. A manifest that names no version, a duplicate
     * path or an entry whose attachment leaves its folder is [AppError.InvalidInput];
     * an IO failure is [AppError.Internal]. The stream is not closed.
     */
    fun write(
        bundle: BackupBundle,
        output: OutputStream,
    ): Outcome<Unit> {
        if (bundle.formatVersion < 1) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val prepared =
            when (val result = prepare(bundle)) {
                is Outcome.Success -> result.value
                is Outcome.Failure -> return result
            }
        return try {
            val zip = ZipOutputStream(BufferedOutputStream(output))
            for (file in prepared.files) {
                zip.putNextEntry(ZipEntry(file.path).apply { time = DETERMINISTIC_TIME })
                zip.write(file.bytes)
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
            Outcome.Success(Unit)
        } catch (exception: IOException) {
            writeFailure(exception)
        } catch (exception: RuntimeException) {
            writeFailure(exception)
        }
    }

    /** The manifest and every file, with paths checked before any byte is written. */
    private fun prepare(bundle: BackupBundle): Outcome<Prepared> {
        val seenPaths = mutableSetOf<String>()
        val kinds = mutableListOf<KindFiles>()
        val files = mutableListOf<BundleFile>()
        for (kind in bundle.kinds) {
            if (!isSafeSegment(kind.id)) {
                return Outcome.Failure(AppError.InvalidInput)
            }
            val ids = mutableSetOf<String>()
            val entriesJson =
                buildJsonArray {
                    for (entry in kind.entries) {
                        if (entry.id.isEmpty() || !ids.add(entry.id)) {
                            return Outcome.Failure(AppError.InvalidInput)
                        }
                        for (file in entry.files) {
                            if (!isPathInside(file.path, kind.id) || !seenPaths.add(file.path)) {
                                return Outcome.Failure(AppError.InvalidInput)
                            }
                        }
                        add(encodeEntry(entry))
                    }
                }
            val indexPath = kind.id + "/" + INDEX_FILE
            if (!seenPaths.add(indexPath)) {
                return Outcome.Failure(AppError.InvalidInput)
            }
            val indexBytes =
                buildJsonObject {
                    put(KIND_KEY, kind.id)
                    put(ENTRIES_KEY, entriesJson)
                }.toString().toByteArray()
            val kindFiles = mutableListOf<BundleFile>()
            kindFiles += BundleFile(indexPath, indexBytes)
            for (entry in kind.entries) {
                kindFiles += entry.files
            }
            files += kindFiles
            kinds +=
                KindFiles(
                    id = kind.id,
                    count = kind.entries.size,
                    files = kindFiles.map(::manifestFile),
                )
        }
        val manifest =
            buildJsonObject {
                put(FORMAT_KEY, BACKUP_FORMAT_ID)
                put(FORMAT_VERSION_KEY, bundle.formatVersion)
                put(APP_VERSION_KEY, bundle.appVersion)
                put(CREATED_AT_KEY, Instant.ofEpochMilli(bundle.createdAtMillis).toString())
                put(CREATED_AT_MILLIS_KEY, bundle.createdAtMillis)
                put(
                    KINDS_KEY,
                    buildJsonArray {
                        kinds.forEach { kind ->
                            add(
                                buildJsonObject {
                                    put(KIND_KEY, kind.id)
                                    put(FOLDER_KEY, kind.id)
                                    put(COUNT_KEY, kind.count)
                                    put(
                                        FILES_KEY,
                                        buildJsonArray {
                                            kind.files.forEach { file ->
                                                add(
                                                    buildJsonObject {
                                                        put(PATH_KEY, file.path)
                                                        put(BYTES_KEY, file.bytes)
                                                        put(SHA256_KEY, file.sha256)
                                                    },
                                                )
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
                put(
                    EXCLUDED_KEY,
                    buildJsonArray {
                        BACKUP_EXCLUSIONS.forEach { excluded ->
                            add(
                                buildJsonObject {
                                    put(KIND_KEY, excluded.id)
                                    put(DESCRIPTION_KEY, excluded.description)
                                },
                            )
                        }
                    },
                )
            }.toString().toByteArray()
        return Outcome.Success(
            Prepared(
                files = listOf(BundleFile(MANIFEST_FILE, manifest)) + files,
            ),
        )
    }

    private fun encodeEntry(entry: BundleEntry): JsonObject =
        buildJsonObject {
            put(ID_KEY, entry.id)
            put(VALUE_KEY, entry.value)
            if (entry.files.isNotEmpty()) {
                put(
                    FILES_KEY,
                    JsonArray(entry.files.map { file -> JsonPrimitive(file.path) }),
                )
            }
        }

    private fun manifestFile(file: BundleFile): ManifestFile =
        ManifestFile(
            path = file.path,
            bytes = file.bytes.size.toLong(),
            sha256 = sha256Hex(file.bytes),
        )

    private fun isPathInside(
        path: String,
        folder: String,
    ): Boolean =
        path.startsWith(folder + "/") &&
            path.length > folder.length + 1 &&
            !path.contains('\\') &&
            path.split('/').none { segment -> segment.isEmpty() || segment == "." || segment == ".." }

    private fun isSafeSegment(value: String): Boolean =
        value.isNotEmpty() &&
            value != "." &&
            value != ".." &&
            value.none { character -> character == '/' || character == '\\' } &&
            value.length <= MAX_SEGMENT_LENGTH

    private fun writeFailure(exception: Exception): Outcome.Failure =
        Outcome.Failure(AppError.Internal(ERROR_ID, exception.javaClass.simpleName))

    private class Prepared(
        val files: List<BundleFile>,
    )

    private class KindFiles(
        val id: String,
        val count: Int,
        val files: List<ManifestFile>,
    )

    private const val DETERMINISTIC_TIME = 0L
    private const val MAX_SEGMENT_LENGTH = 128
    private const val ERROR_ID = "core.backup.write"

    internal const val MANIFEST_FILE = "manifest.json"
    internal const val INDEX_FILE = "index.json"
    internal const val FORMAT_KEY = "format"
    internal const val FORMAT_VERSION_KEY = "formatVersion"
    internal const val APP_VERSION_KEY = "appVersion"
    internal const val CREATED_AT_KEY = "createdAt"
    internal const val CREATED_AT_MILLIS_KEY = "createdAtMillis"
    internal const val KINDS_KEY = "kinds"
    internal const val EXCLUDED_KEY = "excluded"
    internal const val KIND_KEY = "kind"
    internal const val FOLDER_KEY = "folder"
    internal const val COUNT_KEY = "count"
    internal const val FILES_KEY = "files"
    internal const val PATH_KEY = "path"
    internal const val BYTES_KEY = "bytes"
    internal const val SHA256_KEY = "sha256"
    internal const val DESCRIPTION_KEY = "description"
    internal const val ENTRIES_KEY = "entries"
    internal const val ID_KEY = "id"
    internal const val VALUE_KEY = "value"
}
