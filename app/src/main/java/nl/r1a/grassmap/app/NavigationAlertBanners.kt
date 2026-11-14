package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.r1a.grassmap.alerts.AlertBanner
import nl.r1a.grassmap.alerts.AlertEvent
import nl.r1a.grassmap.alerts.AlertKind
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
 * The presenter is pure apart from the injected clock, so a replay and a JVM
 * test drive the same clearing rules. It reads the published navigation state
 * for the distance along the route, not the camera or a draw frame.
 */
internal class NavigationAlertBanners(
    private val clockMillis: () -> Long = { System.nanoTime() / MILLIS_PER_NANOSECOND },
    private val infoLifetimeMillis: Long = INFO_LIFETIME_MILLIS,
    private val warningLifetimeMillis: Long = WARNING_LIFETIME_MILLIS,
    private val maxBanners: Int = MAX_BANNERS,
) {
    private val mutableState = MutableStateFlow(NavigationAlertState())
    private var lastNavigation: NavigationState? = null

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
            if (event.banner.severity == nl.r1a.grassmap.alerts.AlertSeverity.Info) {
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
        mutableState.value =
            NavigationAlertState(
                banners = banners.takeLast(maxBanners),
                signalLost = signalLostAfter(kind, mutableState.value.signalLost),
            )
    }

    /** Reads one published state, dropping expired banners and passed features. */
    @Synchronized
    fun onState(state: NavigationState) {
        lastNavigation = state
        if (state.phase == NavigationPhase.Idle || state.phase == NavigationPhase.Stopped) {
            mutableState.value = NavigationAlertState()
            return
        }
        mutableState.value =
            mutableState.value.copy(
                banners = prune(mutableState.value.banners, state, clockMillis()),
            )
    }

    /**
     * Re-evaluates the lifetimes without a new navigation state, so a banner
     * clears on time while the session is paused or waits for a fix.
     */
    @Synchronized
    fun onTick() {
        mutableState.value =
            mutableState.value.copy(
                banners = prune(mutableState.value.banners, lastNavigation, clockMillis()),
            )
    }

    /** Drops every banner and the no-fix relation, for a new trip. */
    @Synchronized
    fun clear() {
        mutableState.value = NavigationAlertState()
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

    private fun signalLostAfter(
        kind: AlertKind,
        lost: Boolean,
    ): Boolean =
        when (kind) {
            AlertKind.SignalLost -> true
            AlertKind.SignalRegained -> false
            else -> lost
        }

    private companion object {
        const val MAX_BANNERS = 2
        const val INFO_LIFETIME_MILLIS = 4_000L
        const val WARNING_LIFETIME_MILLIS = 8_000L
        const val CLEAR_MARGIN_METERS = 5.0
        const val MILLIS_PER_NANOSECOND = 1_000_000L
    }
}
