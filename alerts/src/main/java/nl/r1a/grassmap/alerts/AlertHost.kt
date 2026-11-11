package nl.r1a.grassmap.alerts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.navigation.RouteRelation
import nl.r1a.grassmap.plugin.api.model.LocationFix

/**
 * The alert seam (T-0317): it reads the navigation state and the fix flow and
 * produces typed [AlertEvent]s. It holds no clock, no Android type and no
 * coroutine scope of its own, so a JVM test drives it with a fix timeline.
 *
 * Every alert is off by default ([AlertSettings.ALL_OFF]) and has one switch.
 * A switch is read when the alert would fire; turning one on does not replay
 * an event that happened while it was off.
 *
 * The once-per-event rules:
 * - **Signal** - the gap between two consecutive fix times is measured on every
 *   fix, and a gap of at least [SIGNAL_GAP_MILLIS] emits [AlertKind.SignalLost]
 *   and then [AlertKind.SignalRegained] on the first fix after it. A gap is
 *   announced once, at that fix, because a pure host has no wall clock: the
 *   later fix is the evidence that the earlier one was missing. A fix time
 *   that does not advance starts a new baseline and announces nothing.
 * - **Off route** - announced on the transition into [RouteRelation.OffRoute]
 *   and not again until the relation leaves OffRoute. The session publishes
 *   OffStepOnRoute while it confirms, so the announcement is the confirmed
 *   episode, not its first fix.
 * - **Reroute** - announced once for each change of
 *   [NavigationState.routeRevision]. The first state only sets the baseline,
 *   so a host attached mid-session does not announce a reroute from before it
 *   was attached. The banner carries the replacement route's own
 *   `distanceMeters`.
 * - **Arrival** - announced once on the transition into
 *   [NavigationPhase.Arrived]; a second Arrived state is the same arrival. The
 *   latch re-arms only when the phase leaves Arrived, which starts a new trip.
 *
 * Latches advance even while their switch is off, so enabling an alert in the
 * middle of an episode waits for the next episode. Sound and vibration are
 * values ([AlertEffect]); the host forwards them to [AlertEffectSink], and the
 * event carries the same list. The stream is live: a consumer subscribes
 * before the session starts, or it misses the alerts already emitted.
 */
class AlertHost(
    initialSettings: AlertSettings = AlertSettings.ALL_OFF,
    private val effectSink: AlertEffectSink = NoopAlertEffectSink,
) {
    private val mutableSettings = MutableStateFlow(initialSettings)

    /** The active switches; a screen can read them to draw the settings page. */
    val settings: StateFlow<AlertSettings> = mutableSettings.asStateFlow()

    private val mutableEvents = MutableSharedFlow<AlertEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** The typed alert stream, in decision order. */
    val events: SharedFlow<AlertEvent> = mutableEvents.asSharedFlow()

    private var lastFixTimeMillis: Long? = null
    private var offRouteEpisode = false
    private var lastRouteRevision: Long? = null
    private var arrived = false
    private var sequence = 0L

    /** Turns [kind] on, sharing its switch with the kind's other half. */
    fun enable(kind: AlertKind) {
        setEnabled(kind, true)
    }

    /** Turns [kind] off; it stays silent until enabled again. */
    fun disable(kind: AlertKind) {
        setEnabled(kind, false)
    }

    /** Sets the switch that owns [kind]. */
    fun setEnabled(
        kind: AlertKind,
        enabled: Boolean,
    ) {
        mutableSettings.update { it.with(kind, enabled) }
    }

    /**
     * Reads one fix time. A gap of at least [SIGNAL_GAP_MILLIS] from the
     * previous fix announces lost then regained, in that order.
     */
    @Synchronized
    fun onFix(fix: LocationFix) {
        val previous = lastFixTimeMillis
        lastFixTimeMillis = fix.timeMillis
        if (previous == null || fix.timeMillis <= previous) {
            return
        }
        val gapMillis = fix.timeMillis - previous
        if (gapMillis < SIGNAL_GAP_MILLIS || !mutableSettings.value.signal) {
            return
        }
        announce(AlertKind.SignalLost, fix.timeMillis, gapMillis = gapMillis)
        announce(AlertKind.SignalRegained, fix.timeMillis, gapMillis = gapMillis)
    }

    /** Reads one published navigation state. */
    @Synchronized
    fun onState(state: NavigationState) {
        val fixTimeMillis = state.fix?.timeMillis ?: lastFixTimeMillis
        val revision = state.routeRevision
        val previousRevision = lastRouteRevision
        lastRouteRevision = revision
        if (previousRevision != null && revision != previousRevision && fixTimeMillis != null && mutableSettings.value.reroute) {
            announce(
                AlertKind.Rerouted,
                fixTimeMillis = fixTimeMillis,
                routeDistanceMeters = state.activeRoute?.distanceMeters,
            )
        }

        val isOffRoute = state.relation == RouteRelation.OffRoute
        if (isOffRoute && !offRouteEpisode && fixTimeMillis != null && mutableSettings.value.offRoute) {
            announce(AlertKind.OffRoute, fixTimeMillis)
        }
        offRouteEpisode = isOffRoute

        val hasArrived = state.phase == NavigationPhase.Arrived
        if (hasArrived && !arrived && fixTimeMillis != null && mutableSettings.value.arrival) {
            announce(AlertKind.Arrived, fixTimeMillis)
        }
        arrived = hasArrived
    }

    /**
     * Feeds a live session into this host: [states] through [onState] and
     * [fixes] through [onFix]. The job runs until the caller cancels it or both
     * flows end; tie it to the screen or session scope so it does not outlive
     * the trip.
     */
    fun attach(
        scope: CoroutineScope,
        states: Flow<NavigationState>,
        fixes: Flow<LocationFix>,
    ): Job =
        scope.launch {
            coroutineScope {
                launch { states.collect { onState(it) } }
                launch { fixes.collect { onFix(it) } }
            }
        }

    private fun announce(
        kind: AlertKind,
        fixTimeMillis: Long,
        routeDistanceMeters: Double? = null,
        gapMillis: Long? = null,
    ) {
        sequence += 1
        val effects = listOf(AlertEffect.Sound(kind), AlertEffect.Vibration(kind))
        val banner = AlertBanner(kind, severityOf(kind), routeDistanceMeters, gapMillis)
        mutableEvents.tryEmit(
            AlertEvent(
                sequence = sequence,
                kind = kind,
                banner = banner,
                effects = effects,
                fixTimeMillis = fixTimeMillis,
            ),
        )
        effects.forEach(effectSink::emit)
    }

    private fun severityOf(kind: AlertKind): AlertSeverity =
        when (kind) {
            AlertKind.SignalLost, AlertKind.OffRoute -> AlertSeverity.Warning
            AlertKind.SignalRegained, AlertKind.Rerouted, AlertKind.Arrived -> AlertSeverity.Info
        }

    companion object {
        /** The fix-time gap that counts as a lost signal: 10 s. */
        const val SIGNAL_GAP_MILLIS: Long = 10_000L

        private const val EVENT_BUFFER = 64
    }
}
