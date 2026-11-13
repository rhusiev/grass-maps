package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.StateFlow
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.map
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.manifest.GeneratedProvider
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackStyle
import nl.r1a.grassmap.plugin.api.provider.FileFormat
import nl.r1a.grassmap.plugin.api.provider.FileFormatContract
import nl.r1a.grassmap.tracks.FileFormatResolver
import nl.r1a.grassmap.tracks.FileTrackStore
import nl.r1a.grassmap.tracks.ImportReport
import nl.r1a.grassmap.tracks.InMemoryTrackStore
import nl.r1a.grassmap.tracks.StoredTrack
import nl.r1a.grassmap.tracks.TrackDocumentReader
import nl.r1a.grassmap.tracks.TrackElevationCorrection
import nl.r1a.grassmap.tracks.TrackId
import nl.r1a.grassmap.tracks.TrackImporter
import nl.r1a.grassmap.tracks.TrackStore
import nl.r1a.grassmap.tracks.TrackSummary
import java.io.File

/**
 * The app's handle on the track library. It resolves `file.gpx` through the
 * generated provider registry, so a missing or failing plugin is a typed value
 * and never an exception.
 *
 * With a [tracksRoot] the library lives on disk under that directory (T-0401):
 * `<hash>.gpx` documents with `<id>.json` sidecars, written atomically. Without
 * one, such as in a unit test, it is process-scoped memory. [elevation] is the
 * mounted region's DEM of T-0403; null means no region is mounted, so a track
 * keeps the heights it arrived with. The library UI is T-0414; the debug
 * command and the map layer are its callers for now.
 */
class TrackAccess internal constructor(
    private val registry: ProviderRegistry,
    private val providers: List<GeneratedProvider>,
    tracksRoot: File? = null,
    elevation: ElevationAccess? = null,
) {
    private val reader = TrackDocumentReader { bytes -> readDocument(bytes) }
    private val correction = TrackElevationCorrection(elevation)
    private val store: TrackStore =
        tracksRoot?.let { root ->
            FileTrackStore(root, reader, elevationCorrection = correction)
        } ?: InMemoryTrackStore()
    private val importer =
        TrackImporter(
            formats = FileFormatResolver { extension -> resolve(extension) },
            store = store,
            elevationCorrection = correction,
        )

    /** The tracks the map draws, current after every import, remove and style change. */
    val drawn: StateFlow<List<MapTrack>> = store.drawn

    suspend fun import(
        fileName: String?,
        bytes: ByteArray,
        cancellation: CancellationHandle = CancellationHandle.None,
    ): Outcome<ImportReport> = importer.importFile(fileName, bytes, cancellation)

    suspend fun stored(id: TrackId): Outcome<StoredTrack> = importer.stored(id)

    suspend fun storedTracks(): List<StoredTrack> = importer.storedTracks()

    suspend fun summaries(): List<TrackSummary> = store.summaries()

    suspend fun setStyle(
        id: TrackId,
        style: TrackStyle,
    ): Outcome<Unit> = store.setStyle(id, style)

    suspend fun remove(id: TrackId): Outcome<Unit> = store.remove(id)

    /** Re-reads the library directory; harmless for the in-memory store. */
    suspend fun reload(): Outcome<Unit> = store.reload()

    /** Resolves the enabled file format for an extension (T-0410 export). */
    internal suspend fun formatFor(extension: String): Outcome<FileFormat> = resolve(extension)

    /** Reads one stored document with the format that wrote it. */
    private suspend fun readDocument(bytes: ByteArray): Outcome<List<Track>> {
        val format =
            when (val resolved = resolve("gpx")) {
                is Outcome.Success -> resolved.value
                is Outcome.Failure -> return resolved
            }
        return format.importFile(bytes, CancellationHandle.None).map { result -> result.entities.tracks }
    }

    private suspend fun resolve(extension: String): Outcome<FileFormat> {
        var failure: AppError? = null
        for (provider in providers.filter { it.contract == FileFormatContract.REFERENCE }) {
            val outcome =
                registry.invoke(provider.pluginId, provider.contract) { loaded -> loaded as? FileFormat }
            when (outcome) {
                is Outcome.Success -> {
                    val format = outcome.value ?: continue
                    if (extension in format.extensions) {
                        return Outcome.Success(format)
                    }
                    failure = AppError.Unsupported
                }

                is Outcome.Failure -> {
                    failure = outcome.error
                }
            }
        }
        return Outcome.Failure(failure ?: AppError.NotFound)
    }
}
