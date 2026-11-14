package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.DocumentSaveResult
import nl.r1a.grassmap.tracks.TrackId

/**
 * What one Save tap in the track library produced (T-0410).
 *
 * [Cancelled] is the user dismissing the system picker, which is an answer and
 * not a failure. [Failed] keeps the typed error the export or the write
 * reported, so the screen names a reason instead of a generic refusal.
 */
sealed interface TrackSaveResult {
    data object Saved : TrackSaveResult

    data object Cancelled : TrackSaveResult

    data class Failed(
        val error: AppError,
    ) : TrackSaveResult
}

/** What a save reports, resolved by the app's own string service (T-0410). */
internal const val TRACK_EXPORT_SAVED_KEY = "track_export_saved"
internal const val TRACK_EXPORT_FAILED_KEY = "track_export_failed"

/**
 * The one entry point from the library's Save action to the system picker
 * (T-0410): build the selected track's GPX, then let `DocumentSaveCoordinator`
 * show `ACTION_CREATE_DOCUMENT` under the activity that owns the launcher.
 *
 * The picker's suggested name is the export's own file name, so the track's name
 * reaches the picker as `<track name>.gpx`. Nothing here resolves a filesystem
 * path or asks for a storage permission.
 */
suspend fun saveTrack(
    export: TrackExport,
    id: TrackId,
): TrackSaveResult =
    when (val file = export.fileFor(id)) {
        is Outcome.Failure -> {
            TrackSaveResult.Failed(file.error)
        }

        is Outcome.Success -> {
            when (val saved = export.save(file.value)) {
                is Outcome.Failure -> {
                    TrackSaveResult.Failed(saved.error)
                }

                is Outcome.Success -> {
                    when (saved.value) {
                        DocumentSaveResult.SAVED -> TrackSaveResult.Saved
                        DocumentSaveResult.CANCELLED -> TrackSaveResult.Cancelled
                    }
                }
            }
        }
    }
