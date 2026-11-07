package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.ImportWarning
import nl.r1a.grassmap.plugin.api.provider.FileFormat
import java.security.MessageDigest

/**
 * Finds the file format that reads an extension. The app implements this over
 * `ProviderRegistry`, so `tracks` has no dependency on a plugin id or on the
 * core host.
 */
fun interface FileFormatResolver {
    suspend fun resolve(extension: String): Outcome<FileFormat>
}

/** A stored track plus the parts of its document that the format skipped. */
data class ImportedTrack(
    val record: StoredTrack,
    val warnings: List<ImportWarning>,
)

/**
 * The one import path: resolve a format, read the document, store the first
 * track and compute its profile. A document with no track is [Unsupported];
 * every skipped point stays a warning on the result. Multiple tracks and the
 * route/waypoint entities are T-0401's.
 */
class TrackImporter(
    private val formats: FileFormatResolver,
    private val store: TrackStore,
    private val analyzer: TrackAnalyzer = TrackAnalyzer(),
) {
    suspend fun importFile(
        fileName: String?,
        bytes: ByteArray,
        cancellation: CancellationHandle,
    ): Outcome<ImportedTrack> {
        if (cancellation.isCancelled()) {
            return Outcome.Failure(AppError.Cancelled)
        }
        val extension = extensionOf(fileName) ?: return Outcome.Failure(AppError.InvalidInput)
        val format =
            when (val resolved = formats.resolve(extension)) {
                is Outcome.Success -> resolved.value
                is Outcome.Failure -> return resolved
            }
        val result =
            when (val imported = format.importFile(bytes, cancellation)) {
                is Outcome.Success -> imported.value
                is Outcome.Failure -> return imported
            }
        val track = result.entities.tracks.firstOrNull() ?: return Outcome.Failure(AppError.Unsupported)
        val record =
            StoredTrack(
                id = TrackId(contentHash(bytes)),
                name = track.name ?: fileName,
                track = track,
                analysis = analyzer.analyze(track),
            )
        return when (val saved = store.save(record)) {
            is Outcome.Success -> Outcome.Success(ImportedTrack(record, result.warnings))
            is Outcome.Failure -> saved
        }
    }

    suspend fun stored(id: TrackId): Outcome<StoredTrack> = store.get(id)

    suspend fun storedTracks(): List<StoredTrack> = store.all()

    private fun extensionOf(fileName: String?): String? {
        val name = fileName?.substringAfterLast('/') ?: return null
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension.takeIf { it.isNotEmpty() && it != name }
    }

    private fun contentHash(bytes: ByteArray): String =
        MessageDigest
            .getInstance(SHA_256)
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }

    private companion object {
        const val SHA_256 = "SHA-256"
        const val BYTE_MASK = 0xff
    }
}
