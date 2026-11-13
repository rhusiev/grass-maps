package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.ImportResult
import nl.r1a.grassmap.plugin.api.model.ImportWarning
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.provider.FileFormat

/**
 * Finds the file format that reads an extension. The app implements this over
 * `ProviderRegistry`, so `tracks` has no dependency on a plugin id or on the
 * core host.
 */
fun interface FileFormatResolver {
    suspend fun resolve(extension: String): Outcome<FileFormat>
}

/**
 * Every track stored by one import, plus the parts of the document the format
 * skipped. A one-track document has one record; a multi-track document has one
 * record per track, each with its own document index in [StoredTrack.trackIndex].
 */
data class ImportReport(
    val tracks: List<StoredTrack>,
    val warnings: List<ImportWarning>,
) {
    /** The stable diagnostic lines for [warnings], one per skipped part. */
    fun warningLines(): List<String> = warnings.map { it.describe() }
}

/**
 * The one import path: resolve a format, read the document, store every track
 * it contains and compute each profile. A document with no track is
 * [AppError.Unsupported]; every skipped point stays a warning on the result.
 *
 * A missing height is filled from [elevationCorrection] before the profile is
 * computed, so the stored analysis includes the DEM heights (T-0403).
 *
 * The id of each record is the document hash ([TrackIdentity]), so a repeated
 * import updates the same records and keeps their styling. A failed save stops
 * the import; records already written stay, and a retry is idempotent.
 */
class TrackImporter(
    private val formats: FileFormatResolver,
    private val store: TrackStore,
    private val analyzer: TrackAnalyzer = TrackAnalyzer(),
    private val elevationCorrection: TrackElevationCorrection = TrackElevationCorrection(),
) {
    suspend fun importFile(
        fileName: String?,
        bytes: ByteArray,
        cancellation: CancellationHandle,
    ): Outcome<ImportReport> {
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
        return storeAll(fileName, bytes, result)
    }

    suspend fun stored(id: TrackId): Outcome<StoredTrack> = store.get(id)

    suspend fun storedTracks(): List<StoredTrack> = store.all()

    private suspend fun storeAll(
        fileName: String?,
        bytes: ByteArray,
        result: ImportResult,
    ): Outcome<ImportReport> {
        val imported = result.entities.tracks
        if (imported.isEmpty()) {
            return Outcome.Failure(AppError.Unsupported)
        }
        val records = mutableListOf<StoredTrack>()
        for ((index, track) in imported.withIndex()) {
            val record = storedRecord(fileName, bytes, index, track, imported.size)
            when (val saved = store.save(record, bytes)) {
                is Outcome.Success -> records += saved.value
                is Outcome.Failure -> return saved
            }
        }
        return Outcome.Success(ImportReport(tracks = records, warnings = result.warnings))
    }

    private suspend fun storedRecord(
        fileName: String?,
        bytes: ByteArray,
        index: Int,
        track: Track,
        count: Int,
    ): StoredTrack {
        val id = TrackIdentity.of(bytes, index, count)
        val corrected = elevationCorrection.correct(track)
        return StoredTrack(
            id = id,
            name = track.name ?: fileName,
            track = corrected.track,
            analysis = analyzer.analyze(corrected.track),
            style = TrackPalette.styleFor(id),
            trackIndex = index,
            elevation = corrected.report,
        )
    }

    private fun extensionOf(fileName: String?): String? {
        val name = fileName?.substringAfterLast('/') ?: return null
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension.takeIf { it.isNotEmpty() && it != name }
    }
}
