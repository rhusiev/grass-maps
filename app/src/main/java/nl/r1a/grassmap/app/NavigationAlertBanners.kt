package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.r1a.grassmap.alerts.AlertBanner
import nl.r1a.grassmap.alerts.AlertEvent
import nl.r1a.grassmap.alerts.AlertHost
import nl.r1a.grassmap.alerts.AlertKind
import nl.r1a.grassmap.alerts.AlertSeverity
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState

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
 * age of the last published fix, never from the retrospective
 * [AlertKind.SignalLost] and [AlertKind.SignalRegained] events: those announce
 * a gap after the recovering fix, when the live state is already fresh again.
 * The relation is drawn while the last fix is at least
 * [AlertHost.SIGNAL_GAP_MILLIS] old and clears on the next fix. Checked on
 * every tick so it turns on while fixes are missing.
 *
 * The presenter is pure apart from the injected clocks, so a replay and a JVM
 * test drive the same rules. It reads the published navigation state for the
 * distance along the route, not the camera or a draw frame. [clockMillis] is
 * the elapsed time that ages banners; [fixClockMillis] is epoch time, the same
 * base as [nl.r1a.grassmap.plugin.api.model.LocationFix.timeMillis].
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
    private var lastFixTimeMillis: Long? = null

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
        state.fix?.let { fix -> lastFixTimeMillis = fix.timeMillis }
        if (state.phase == NavigationPhase.Idle || state.phase == NavigationPhase.Stopped) {
            lastFixTimeMillis = null
            mutableState.value = NavigationAlertState()
            return
        }
        if (!state.phase.isGuidanceActive()) {
            // A finished trip must not lend its fix age to the next trip, whose
            // first state has no fix until the first accepted one arrives.
            lastFixTimeMillis = null
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
     * tick ages the last fix, which is how the no-fix relation turns on while
     * no fix is arriving to publish a state.
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
        lastFixTimeMillis = null
        mutableState.value = NavigationAlertState()
    }

    /**
     * The last published fix is at least [AlertHost.SIGNAL_GAP_MILLIS] old.
     * Only a phase that can still use a fix has the relation; a finished trip
     * has no live relation to draw, and the next trip starts from its own fix.
     */
    private fun liveSignalLost(): Boolean {
        val phase = lastNavigation?.phase ?: return false
        if (!phase.isGuidanceActive()) {
            return false
        }
        val lastFix = lastFixTimeMillis ?: return false
        return fixClockMillis() - lastFix >= AlertHost.SIGNAL_GAP_MILLIS
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
