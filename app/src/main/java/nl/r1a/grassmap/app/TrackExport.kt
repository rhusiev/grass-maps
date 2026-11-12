package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.DocumentSaveResult
import nl.r1a.grassmap.plugin.api.host.PlatformActions
import nl.r1a.grassmap.plugin.api.host.PlatformFile
import nl.r1a.grassmap.plugin.api.model.ImportedEntities
import nl.r1a.grassmap.plugins.share.TrackFileShares
import nl.r1a.grassmap.tracks.StoredTrack
import nl.r1a.grassmap.tracks.TrackId

/**
 * Turns stored tracks into the one GPX document the save or share path hands to
 * the platform (T-0410).
 *
 * The library UI is T-0414; this class is the capability it will call with one
 * selected track or the selected list. Export goes through the resolved
 * `file.format` provider rather than naming `plugins/gpx`, so a second format
 * provider can take over without changing this path. The store's style travels
 * as a namespaced GPX extension; geometry, names, elevation and time stay
 * standard GPX and survive the importer unchanged.
 */
class TrackExport(
    private val tracks: TrackAccess,
    private val actions: PlatformActions,
) {
    suspend fun fileFor(id: TrackId): Outcome<PlatformFile> = fileForList(listOf(id))

    suspend fun fileForAll(): Outcome<PlatformFile> {
        val stored = tracks.storedTracks()
        if (stored.isEmpty()) {
            return Outcome.Failure(AppError.NotFound)
        }
        return fileOf(stored)
    }

    suspend fun fileForList(ids: List<TrackId>): Outcome<PlatformFile> {
        if (ids.isEmpty() || ids.toSet().size != ids.size) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val stored = mutableListOf<StoredTrack>()
        for (id in ids) {
            when (val record = tracks.stored(id)) {
                is Outcome.Success -> stored += record.value
                is Outcome.Failure -> return record
            }
        }
        return fileOf(stored)
    }

    suspend fun share(file: PlatformFile): Outcome<Unit> = actions.shareFile(file)

    suspend fun save(file: PlatformFile): Outcome<DocumentSaveResult> = actions.saveFile(file)

    private suspend fun fileOf(stored: List<StoredTrack>): Outcome<PlatformFile> {
        val format =
            when (val resolved = tracks.formatFor(TrackFileShares.EXTENSION)) {
                is Outcome.Success -> resolved.value
                is Outcome.Failure -> return resolved
            }
        val entities =
            ImportedEntities(
                tracks = stored.map { record -> record.track.copy(style = record.style) },
            )
        val bytes =
            when (val exported = format.export(entities, CancellationHandle.None)) {
                is Outcome.Success -> exported.value
                is Outcome.Failure -> return exported
            }
        return Outcome.Success(
            PlatformFile(
                fileName =
                    TrackFileShares.fileName(
                        name = stored.singleOrNull()?.name,
                        id = stored.singleOrNull()?.id?.value,
                        count = stored.size,
                    ),
                mimeType = TrackFileShares.mimeType(format.mimeTypes),
                bytes = bytes,
            ),
        )
    }
}
