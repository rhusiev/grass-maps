package nl.r1a.grassmap.plugins.track.layer

import kotlinx.coroutines.flow.StateFlow
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.MapTrack

/**
 * Draws the track library on the bound map (T-0401, T-0408).
 *
 * The controller owns no state: [tracks] is the store's drawable list and
 * [host] is the newest bound map surface. [run] keeps the map in step with the
 * library until its coroutine is cancelled; [refresh] draws the current list
 * once, which is what a newly bound surface needs. A surface that is gone is
 * [AppError.NotReady] and never an exception, so the layer waits for the next
 * library change instead of stopping.
 *
 * [colouring] names what the line should be coloured by. [TrackColouring.Own]
 * is the only mode the layer can draw; a gradient request is logged and the
 * tracks keep their own colour, because no surface or height reaches this
 * layer (see [TrackColouring]).
 */
class TrackLayer(
    private val tracks: StateFlow<List<MapTrack>>,
    private val host: () -> MapHost?,
    private val log: (String) -> Unit = {},
    private val colouring: TrackColouring = TrackColouring.Own,
) {
    /** Draws the current library on the bound host. */
    suspend fun refresh(): Outcome<Unit> = draw(tracks.value)

    /** Redraws on every library change; collect it in the map screen's scope. */
    suspend fun run() {
        tracks.collect { drawn -> draw(drawn) }
    }

    private suspend fun draw(drawn: List<MapTrack>): Outcome<Unit> {
        if (colouring == TrackColouring.Gradient) {
            log("track gradient colouring unavailable: no surface data and no heights on the layer")
        }
        val map = host() ?: return Outcome.Failure(AppError.NotReady)
        return map.setTracks(drawn).onFailure { error -> log("track layer not drawn: $error") }
    }
}
