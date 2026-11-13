package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.ImportResult
import nl.r1a.grassmap.plugin.api.model.ImportWarning
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackKind
import nl.r1a.grassmap.plugin.api.model.TrackWaypoint
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
    /** The `<wpt>` entries of the document, kept beside every record it produced. */
    val waypoints: List<TrackWaypoint> = emptyList(),
) {
    /** The stable diagnostic lines for [warnings], one per skipped part. */
    fun warningLines(): List<String> = warnings.map { it.describe() }
}

/**
 * The one import path: resolve a format, read the document, store every track
 * it contains and compute each profile. A document with no track is
 * [AppError.Unsupported]; every skipped point stays a warning on the result.
 *
 * A `<rte>` is a record too (T-0411): a document that holds a route beside its
 * track yields one record per element, and the route's record is
 * [TrackKind.Route], so the library and an export keep the distinction. The
 * document's `<wpt>` entries travel with each record it produced, so an export
 * of a stored track writes its waypoints back.
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
        val records = result.entities.records()
        if (records.isEmpty()) {
            return Outcome.Failure(AppError.Unsupported)
        }
        val waypoints = result.entities.waypoints
        val stored = mutableListOf<StoredTrack>()
        for ((index, record) in records.withIndex()) {
            val saved = storedRecord(fileName, bytes, record, index, records.size, waypoints)
            when (val outcome = store.save(saved, bytes)) {
                is Outcome.Success -> stored += outcome.value
                is Outcome.Failure -> return outcome
            }
        }
        return Outcome.Success(ImportReport(tracks = stored, warnings = result.warnings, waypoints = waypoints))
    }

    private suspend fun storedRecord(
        fileName: String?,
        bytes: ByteArray,
        record: DocumentRecord,
        index: Int,
        count: Int,
        waypoints: List<TrackWaypoint>,
    ): StoredTrack {
        val id = TrackIdentity.of(bytes, index, count)
        // A route is a plan, not a travelled line: the DEM is not sampled for
        // it, so its stored profile never carries invented heights.
        val corrected =
            when (record.kind) {
                TrackKind.Route -> CorrectedTrack(record.track, ElevationCorrectionReport(ElevationSource.NoDem))
                TrackKind.Track -> elevationCorrection.correct(record.track)
            }
        return StoredTrack(
            id = id,
            name = record.track.name ?: fileName,
            track = corrected.track,
            analysis = analyzer.analyze(corrected.track),
            style = TrackPalette.styleFor(id),
            trackIndex = index,
            elevation = corrected.report,
            kind = record.kind,
            waypoints = waypoints,
        )
    }

    private fun extensionOf(fileName: String?): String? {
        val name = fileName?.substringAfterLast('/') ?: return null
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension.takeIf { it.isNotEmpty() && it != name }
    }
}
