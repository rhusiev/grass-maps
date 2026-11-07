package nl.r1a.grassmap.app

import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.manifest.GeneratedProvider
import nl.r1a.grassmap.plugin.api.provider.FileFormat
import nl.r1a.grassmap.plugin.api.provider.FileFormatContract
import nl.r1a.grassmap.tracks.FileFormatResolver
import nl.r1a.grassmap.tracks.ImportedTrack
import nl.r1a.grassmap.tracks.InMemoryTrackStore
import nl.r1a.grassmap.tracks.StoredTrack
import nl.r1a.grassmap.tracks.TrackId
import nl.r1a.grassmap.tracks.TrackImporter
import nl.r1a.grassmap.tracks.TrackStore

/**
 * The app's handle on the track import path. It resolves `file.gpx` through the
 * generated provider registry, so a missing or failing plugin is a typed value
 * and never an exception. T-0401 owns the file store and the library UI.
 */
class TrackAccess internal constructor(
    private val registry: ProviderRegistry,
    providers: List<GeneratedProvider>,
    store: TrackStore = InMemoryTrackStore(),
) {
    private val importer =
        TrackImporter(
            formats = FileFormatResolver { extension -> resolve(extension, providers) },
            store = store,
        )

    suspend fun import(
        fileName: String?,
        bytes: ByteArray,
        cancellation: CancellationHandle = CancellationHandle.None,
    ): Outcome<ImportedTrack> = importer.importFile(fileName, bytes, cancellation)

    suspend fun stored(id: TrackId): Outcome<StoredTrack> = importer.stored(id)

    suspend fun storedTracks(): List<StoredTrack> = importer.storedTracks()

    private suspend fun resolve(
        extension: String,
        providers: List<GeneratedProvider>,
    ): Outcome<FileFormat> {
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
