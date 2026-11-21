package nl.r1a.grassmaps.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.r1a.grassmaps.alerts.AlertBanner
import nl.r1a.grassmaps.alerts.AlertEvent
import nl.r1a.grassmaps.alerts.AlertHost
import nl.r1a.grassmaps.alerts.AlertKind
import nl.r1a.grassmaps.alerts.AlertSeverity
import nl.r1a.grassmaps.navigation.NavigationPhase
import nl.r1a.grassmaps.navigation.NavigationState
import nl.r1a.grassmaps.plugin.api.model.LocationFix

/** One banner still on screen, with the values that decide when it clears. */
internal data class ActiveNavigationAlert(
    val sequence: Long,
    val banner: AlertBanner,
    val featureAlongMeters: Double? = null,
    val expiresAtMillis: Long,
)

/** The banners and the no-fix relation a navigation screen draws. */
internal data class NavigationAlertState(
    val banners: List<ActiveNavigationAlert> = emptyList(),
    val signalLost: Boolean = false,
)

/**
 * Keeps the round 5 banner rules over the alert host's event stream: at most two
 * banners, the nearest first; a way alert clears once its feature is behind the
 * traveller; an informational banner clears after four seconds; a warning gets
 * a little longer. Off route and arrival never become banners because the turn
 * card already carries those states.
 *
 * The presenter also owns the live no-fix relation. It is computed from the
 * time since a fix last arrived, never from the retrospective
 * [AlertKind.SignalLost] and [AlertKind.SignalRegained] events: those announce
 * a gap after the recovering fix, when the live state is already fresh again.
 * The relation is drawn while no fix has arrived for
 * [AlertHost.SIGNAL_GAP_MILLIS] and clears on the next arrival. Checked on
 * every tick so it turns on while fixes are missing.
 *
 * The presenter is pure apart from the injected clocks, so a replay and a JVM
 * test drive the same rules. It reads the published navigation state for the
 * distance along the route, not the camera or a draw frame. [clockMillis] is
 * the elapsed time that ages banners; [fixClockMillis] is the wall clock that
 * times fix arrivals. A fix's recorded `timeMillis` is not an arrival time: a
 * GPX replay stamps each point with the time it was recorded, which can be
 * days in the past, so comparing that stamp to the wall clock would report a
 * gap while fixes are arriving and hide one while they are not.
 */
internal class NavigationAlertBanners(
    private val clockMillis: () -> Long = { System.nanoTime() / MILLIS_PER_NANOSECOND },
    private val fixClockMillis: () -> Long = { System.currentTimeMillis() },
    private val infoLifetimeMillis: Long = INFO_LIFETIME_MILLIS,
    private val warningLifetimeMillis: Long = WARNING_LIFETIME_MILLIS,
    private val maxBanners: Int = MAX_BANNERS,
) {
    private val mutableState = MutableStateFlow(NavigationAlertState())
    private var lastNavigation: NavigationState? = null
    private var lastFix: LocationFix? = null
    private var lastFixArrivalMillis: Long? = null

    /** The live banners and the signal relation; the screen collects this flow. */
    val state: StateFlow<NavigationAlertState> = mutableState.asStateFlow()

    /** Reads one alert event; [navigation] is the state published with it. */
    @Synchronized
    fun onEvent(
        event: AlertEvent,
        navigation: NavigationState?,
    ) {
        val kind = event.kind
        if (!event.bannerVisible) {
            // The banner channel is off. Sound or vibration still reached the
            // sink; this screen draws nothing for it.
            return
        }
        if (kind == AlertKind.OffRoute || kind == AlertKind.Arrived) {
            return
        }
        val now = clockMillis()
        lastNavigation = navigation
        val previous = prune(mutableState.value.banners, navigation, now)
        val banners =
            when (kind) {
                AlertKind.SignalRegained -> previous.filterNot { it.banner.kind == AlertKind.SignalLost }.toMutableList()
                AlertKind.SignalLost -> previous.filterNot { it.banner.kind == AlertKind.SignalRegained }.toMutableList()
                else -> previous.toMutableList()
            }
        val featureDistance = event.banner.featureDistanceMeters
        val along =
            featureDistance?.let {
                val total = navigation?.activeRoute?.distanceMeters ?: return@let null
                (total - navigation.remainingDistanceMeters) + it
            }
        val lifetime =
            if (event.banner.severity == AlertSeverity.Info) {
                infoLifetimeMillis
            } else {
                warningLifetimeMillis
            }
        banners +=
            ActiveNavigationAlert(
                sequence = event.sequence,
                banner = event.banner,
                featureAlongMeters = along,
                expiresAtMillis = now + lifetime,
            )
        // The live no-fix relation belongs to the last fix's age, not to this
        // retrospective event, which arrives on the recovering fix.
        mutableState.value = mutableState.value.copy(banners = banners.takeLast(maxBanners))
    }

    /** Reads one published state, dropping expired banners and passing features. */
    @Synchronized
    fun onState(state: NavigationState) {
        lastNavigation = state
        rememberFixArrival(state.fix)
        if (state.phase == NavigationPhase.Idle || state.phase == NavigationPhase.Stopped) {
            resetFixAge()
            mutableState.value = NavigationAlertState()
            return
        }
        if (!state.phase.isGuidanceActive()) {
            // A finished trip must not lend its fix age to the next trip, whose
            // first state has no fix until the first accepted one arrives.
            resetFixAge()
        }
        mutableState.value =
            NavigationAlertState(
                banners = prune(mutableState.value.banners, state, clockMillis()),
                signalLost = liveSignalLost(),
            )
    }

    /**
     * Re-evaluates the lifetimes without a new navigation state, so a banner
     * clears on time while the session is paused or waits for a fix. The same
     * tick ages the last fix arrival, which is how the no-fix relation turns
     * on while no fix is arriving to publish a state.
     */
    @Synchronized
    fun onTick() {
        mutableState.value =
            NavigationAlertState(
                banners = prune(mutableState.value.banners, lastNavigation, clockMillis()),
                signalLost = liveSignalLost(),
            )
    }

    /** Drops every banner and the no-fix relation, for a new trip. */
    @Synchronized
    fun clear() {
        resetFixAge()
        mutableState.value = NavigationAlertState()
    }

    /**
     * No fix has arrived for at least [AlertHost.SIGNAL_GAP_MILLIS]. Only a
     * phase that can still use a fix has the relation; a finished trip has no
     * live relation to draw, and the next trip starts from its own fix.
     */
    private fun liveSignalLost(): Boolean {
        val phase = lastNavigation?.phase ?: return false
        if (!phase.isGuidanceActive()) {
            return false
        }
        val arrived = lastFixArrivalMillis ?: return false
        return fixClockMillis() - arrived >= AlertHost.SIGNAL_GAP_MILLIS
    }

    /**
     * Marks a fix as arrived when the published state carries one we have not
     * seen. A paused or rerouted state copies the previous fix, so neither
     * object identity nor its recorded time changed and the age keeps running.
     */
    private fun rememberFixArrival(fix: LocationFix?) {
        if (fix != null && (fix !== lastFix || fix.timeMillis != lastFix?.timeMillis)) {
            lastFix = fix
            lastFixArrivalMillis = fixClockMillis()
        }
    }

    private fun resetFixAge() {
        lastFix = null
        lastFixArrivalMillis = null
    }

    private fun prune(
        banners: List<ActiveNavigationAlert>,
        navigation: NavigationState?,
        now: Long,
    ): List<ActiveNavigationAlert> {
        val along =
            navigation?.let { state ->
                val total = state.activeRoute?.distanceMeters ?: return@let null
                total - state.remainingDistanceMeters
            }
        return banners.filter { active ->
            if (now >= active.expiresAtMillis) {
                return@filter false
            }
            val feature = active.featureAlongMeters
            if (feature != null && along != null && along >= feature + CLEAR_MARGIN_METERS) {
                return@filter false
            }
            true
        }
    }

    private companion object {
        const val MAX_BANNERS = 2
        const val INFO_LIFETIME_MILLIS = 4_000L
        const val WARNING_LIFETIME_MILLIS = 8_000L
        const val CLEAR_MARGIN_METERS = 5.0
        const val MILLIS_PER_NANOSECOND = 1_000_000L
    }
}
