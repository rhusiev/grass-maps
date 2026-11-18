package nl.r1a.grassmap.app

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Requests to open or close map-screen chrome that is local view state.
 *
 * The layers card and the downloads server form are plain Compose state, so a
 * command cannot reach them. The debug device check sends a request here and
 * the map screen applies it. A request is an event, not a state: a screen that
 * composes later, or again after a rotation, does not replay an old one. A
 * release build never sends one.
 */
object MapChromeSession {
    private val layersEvents = eventFlow()
    private val tokenFormEvents = eventFlow()

    /** Open (true) or close (false) the layers card. */
    val layers: SharedFlow<Boolean> = layersEvents.asSharedFlow()

    /** Show (true) or hide (false) the server and token form on the downloads screen. */
    val tokenForm: SharedFlow<Boolean> = tokenFormEvents.asSharedFlow()

    fun requestLayers(open: Boolean) {
        layersEvents.tryEmit(open)
    }

    fun requestTokenForm(visible: Boolean) {
        tokenFormEvents.tryEmit(visible)
    }

    private fun eventFlow(): MutableSharedFlow<Boolean> =
        MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
