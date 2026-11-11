package nl.r1a.grassmap.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pause and resume for one running [NavigationSession].
 *
 * A paused session does not accept fixes: each fix that arrives while paused is
 * dropped before the follower, the off-route streak and the reroute gate, so it
 * can neither move the guidance nor start a request. Resuming takes the next fix
 * as the new position; a fix that arrived during the pause is never replayed.
 *
 * The flag is a [StateFlow] because the session publishes [NavigationPhase.Paused]
 * when the flag changes, not when the next fix arrives. That is what lets a
 * notification show the paused state with no location update.
 */
class NavigationControl {
    private val mutablePaused = MutableStateFlow(false)

    /** Whether guidance is paused right now. */
    val paused: StateFlow<Boolean> = mutablePaused.asStateFlow()

    /** Pauses guidance; returns whether the flag changed. */
    fun pause(): Boolean = mutablePaused.compareAndSet(expect = false, update = true)

    /** Resumes guidance; returns whether the flag changed. */
    fun resume(): Boolean = mutablePaused.compareAndSet(expect = true, update = false)
}
