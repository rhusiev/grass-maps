package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.r1a.grassmap.tracks.StoredTrack

/**
 * The track the analysis panel draws (T-0404).
 *
 * Process-scoped like the place card, so a rebuilt composition keeps the panel
 * open. The only writer is the debug host command until the map's track
 * selection opens it (T-0415).
 */
object TrackAnalysisSession {
    private val current = MutableStateFlow<StoredTrack?>(null)

    val track: StateFlow<StoredTrack?> = current.asStateFlow()

    fun show(track: StoredTrack) {
        current.value = track
    }

    fun hide() {
        current.value = null
    }
}
