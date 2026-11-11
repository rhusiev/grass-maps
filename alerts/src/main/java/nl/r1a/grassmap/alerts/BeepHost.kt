package nl.r1a.grassmap.alerts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.plugin.api.provider.RouteProfile

/**
 * The turn-beep host (T-0318): it feeds the published navigation states into
 * [BeepSchedule] and sends each due cue to a [BeepSink].
 *
 * Beeps are off by default, like every T-0317 alert. Enabling them takes effect
 * on the next state; disabling them forgets the current approach, so enabling
 * again inside a turn's distance does not beep late. [setProfile] picks the
 * distance and also starts a new schedule.
 *
 * The host owns no clock and no Android type. The app wires
 * [AndroidBeepSink] and a process-scoped collector of the live navigation
 * state; a JVM test wires a recording sink and drives [onState] directly.
 */
class BeepHost(
    private val schedule: BeepSchedule = BeepSchedule(),
    private val sink: BeepSink = NoopBeepSink,
) {
    private val mutableEnabled = MutableStateFlow(false)
    private var emitted = 0

    /** Whether the beep mode is on. */
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    /** How many beeps this host has emitted, for a probe or a test. */
    val beepCount: Int
        get() = emitted

    /** The active travel profile. */
    val profile: RouteProfile
        get() = schedule.profile

    /** The active beep distance, in metres. */
    val thresholdMeters: Double
        get() = schedule.thresholdMeters

    /** Turns the beep mode on or off. */
    fun setEnabled(enabled: Boolean) {
        mutableEnabled.value = enabled
        if (!enabled) {
            schedule.reset()
        }
    }

    /** Switches the beep distance to [profile] and starts a new schedule. */
    fun setProfile(profile: RouteProfile) {
        schedule.setProfile(profile)
    }

    /** Reads one navigation state and sounds a beep when it is due. */
    @Synchronized
    fun onState(state: NavigationState) {
        if (!mutableEnabled.value) {
            return
        }
        schedule.onState(state)?.let { cue ->
            sink.emit(cue)
            emitted += 1
        }
    }

    /** Feeds live navigation states until the caller cancels the job. */
    fun attach(
        scope: CoroutineScope,
        states: Flow<NavigationState>,
    ): Job = scope.launch { states.collect { onState(it) } }
}
