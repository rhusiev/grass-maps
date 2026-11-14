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
import kotlinx.coroutines.launch
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.navigation.RouteRelation
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.ProximityPlaceKind
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmap.plugin.api.model.WayAlertKey

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
 * - **Proximity** - the host has no proximity latch of its own: the producer
 *   (T-0413) decides that one approach is due and the host announces it. Two
 *   independent latches for the same place would disagree about a second
 *   approach, so the scope of "once" stays with the producer.
 *
 * Latches advance even while their switch is off, so enabling an alert in the
 * middle of an episode waits for the next episode. Sound and vibration are
 * values ([AlertEffect]); the host forwards them to [AlertEffectSink], and the
 * event carries the same list. [AlertChannels] decide which of the banner,
 * sound and vibration outputs an announced kind produces; all three off means
 * the host emits nothing at all. The stream is live: a consumer subscribes
 * before the session starts, or it misses the alerts already emitted.
 */
class AlertHost(
    initialSettings: AlertSettings = AlertSettings.ALL_OFF,
    private val effectSink: AlertEffectSink = NoopAlertEffectSink,
    initialChannels: AlertChannels = AlertChannels.ALL_ON,
    private val onSettingsChanged: (AlertSettings) -> Unit = {},
    private val onChannelsChanged: (AlertChannels) -> Unit = {},
) {
    private val mutableSettings = MutableStateFlow(initialSettings)
    private val mutableChannels = MutableStateFlow(initialChannels)

    /** The active switches; a screen can read them to draw the settings page. */
    val settings: StateFlow<AlertSettings> = mutableSettings.asStateFlow()

    /** The active output channels; the screen draws the three reach controls. */
    val channels: StateFlow<AlertChannels> = mutableChannels.asStateFlow()

    private val mutableEvents = MutableSharedFlow<AlertEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** The typed alert stream, in decision order. */
    val events: SharedFlow<AlertEvent> = mutableEvents.asSharedFlow()

    private var lastFixTimeMillis: Long? = null
    private var offRouteEpisode = false
    private var lastRouteRevision: Long? = null
    private var arrived = false
    private var sequence = 0L
    private val announcedWayAlerts = mutableSetOf<WayAlertKey>()
    private var wayAlertRevision: Long? = null

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
        val next =
            synchronized(this) {
                val value = mutableSettings.value.with(kind, enabled)
                mutableSettings.value = value
                value
            }
        onSettingsChanged(next)
    }

    /** Turns one output channel on or off. */
    fun setChannel(
        channel: AlertChannel,
        enabled: Boolean,
    ) {
        val next =
            synchronized(this) {
                val value = mutableChannels.value.with(channel, enabled)
                mutableChannels.value = value
                value
            }
        onChannelsChanged(next)
    }

    /**
     * Replaces the switches and channels without reporting a change, for a new
     * active profile. A surrounding settings store owns the write; this call
     * is the read direction only.
     */
    fun replaceSettings(
        settings: AlertSettings,
        channels: AlertChannels,
    ) {
        synchronized(this) {
            mutableSettings.value = settings
            mutableChannels.value = channels
        }
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
        if (previousRevision != null && revision != previousRevision) {
            announcedWayAlerts.clear()
            if (fixTimeMillis != null && mutableSettings.value.reroute) {
                announce(
                    AlertKind.Rerouted,
                    fixTimeMillis = fixTimeMillis,
                    routeDistanceMeters = state.activeRoute?.distanceMeters,
                )
            }
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

    /**
     * Announces one way alert (T-0319): a tunnel, a railway crossing or a
     * hazard/ford/barrier found on the route. [key] is the feature's identity
     * inside [key]'s route revision, so the same feature is announced once and
     * a reroute, which changes the revision, re-arms it.
     *
     * A switch that is off drops the alert; the key is not recorded, so
     * enabling the switch does not replay a feature that is already behind.
     * The plugin's schedule has already latched the feature by then.
     */
    @Synchronized
    fun onWayAlert(
        kind: AlertKind,
        fixTimeMillis: Long,
        distanceToFeatureMeters: Double,
        key: WayAlertKey,
        featureKind: WayAlertFeatureKind? = null,
    ) {
        require(kind == AlertKind.Tunnel || kind == AlertKind.RailwayCrossing || kind == AlertKind.Hazard) {
            "a way alert carries a way-alert kind"
        }
        if (!mutableSettings.value.isEnabled(kind)) {
            return
        }
        if (wayAlertRevision != key.routeRevision) {
            announcedWayAlerts.clear()
            wayAlertRevision = key.routeRevision
        }
        if (!announcedWayAlerts.add(key)) {
            return
        }
        announce(
            kind = kind,
            fixTimeMillis = fixTimeMillis,
            featureDistanceMeters = distanceToFeatureMeters,
            featureKind = featureKind,
        )
    }

    /**
     * Announces one proximity alert (T-0413): a favourite, a waypoint of the
     * followed track, or a place of a kind the user picked.
     *
     * The producer owns the approach scope. Its schedule latches a place when
     * it first comes inside the place's radius and releases the latch only
     * after the traveller is beyond the radius again, so a fix that stays
     * inside the radius does not repeat and a second approach does. The host
     * therefore announces every call that its switch allows instead of keeping
     * a second latch: two "once" rules would disagree, and an approach is
     * spatial, not a time window.
     *
     * A switch that is off drops the alert before any event or effect is
     * produced. [placeName] is the place's own stored name, not authored
     * English; a null name is a place that has none.
     */
    @Synchronized
    fun onPlaceAlert(
        placeKind: ProximityPlaceKind,
        fixTimeMillis: Long,
        distanceMeters: Double,
        placeName: String? = null,
    ) {
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) {
            "the place distance must be finite and not negative"
        }
        val kind = AlertKind.forProximity(placeKind)
        if (!mutableSettings.value.isEnabled(kind)) {
            return
        }
        announce(
            kind = kind,
            fixTimeMillis = fixTimeMillis,
            placeDistanceMeters = distanceMeters,
            placeName = placeName,
        )
    }

    private fun announce(
        kind: AlertKind,
        fixTimeMillis: Long,
        routeDistanceMeters: Double? = null,
        gapMillis: Long? = null,
        featureDistanceMeters: Double? = null,
        featureKind: WayAlertFeatureKind? = null,
        placeDistanceMeters: Double? = null,
        placeName: String? = null,
    ) {
        val channels = mutableChannels.value
        val effects =
            buildList {
                if (channels.sound) add(AlertEffect.Sound(kind))
                if (channels.vibration) add(AlertEffect.Vibration(kind))
            }
        if (!channels.banner && effects.isEmpty()) {
            return
        }
        sequence += 1
        val banner =
            AlertBanner(
                kind = kind,
                severity = severityOf(kind),
                routeDistanceMeters = routeDistanceMeters,
                gapMillis = gapMillis,
                featureDistanceMeters = featureDistanceMeters,
                featureKind = featureKind,
                placeDistanceMeters = placeDistanceMeters,
                placeName = placeName,
            )
        mutableEvents.tryEmit(
            AlertEvent(
                sequence = sequence,
                kind = kind,
                banner = banner,
                effects = effects,
                fixTimeMillis = fixTimeMillis,
                bannerVisible = channels.banner,
            ),
        )
        effects.forEach(effectSink::emit)
    }

    private fun severityOf(kind: AlertKind): AlertSeverity =
        when (kind) {
            AlertKind.SignalLost, AlertKind.OffRoute -> AlertSeverity.Warning
            AlertKind.SignalRegained, AlertKind.Rerouted, AlertKind.Arrived -> AlertSeverity.Info
            AlertKind.Tunnel -> AlertSeverity.Info
            AlertKind.RailwayCrossing, AlertKind.Hazard -> AlertSeverity.Warning
            AlertKind.Favourite, AlertKind.TrackWaypoint, AlertKind.Poi -> AlertSeverity.Info
        }

    companion object {
        /** The fix-time gap that counts as a lost signal: 10 s. */
        const val SIGNAL_GAP_MILLIS: Long = 10_000L

        private const val EVENT_BUFFER = 64
    }
}
