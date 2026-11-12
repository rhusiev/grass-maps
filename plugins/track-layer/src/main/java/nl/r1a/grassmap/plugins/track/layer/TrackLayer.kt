package nl.r1a.grassmap.plugins.track.layer

import kotlinx.coroutines.flow.StateFlow
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.MapTrack

/**
 * Draws the track library on the bound map (T-0401).
 *
 * The controller owns no state: [tracks] is the store's drawable list and
 * [host] is the newest bound map surface. [run] keeps the map in step with the
 * library until its coroutine is cancelled; [refresh] draws the current list
 * once, which is what a newly bound surface needs. A surface that is gone is
 * [AppError.NotReady] and never an exception, so the layer waits for the next
 * library change instead of stopping.
 */
class TrackLayer(
    private val tracks: StateFlow<List<MapTrack>>,
    private val host: () -> MapHost?,
    private val log: (String) -> Unit = {},
) {
    /** Draws the current library on the bound host. */
    suspend fun refresh(): Outcome<Unit> = draw(tracks.value)

    /** Redraws on every library change; collect it in the map screen's scope. */
    suspend fun run() {
        tracks.collect { drawn -> draw(drawn) }
    }

    private suspend fun draw(drawn: List<MapTrack>): Outcome<Unit> {
        val map = host() ?: return Outcome.Failure(AppError.NotReady)
        return map.setTracks(drawn).onFailure { error -> log("track layer not drawn: $error") }
    }
}
