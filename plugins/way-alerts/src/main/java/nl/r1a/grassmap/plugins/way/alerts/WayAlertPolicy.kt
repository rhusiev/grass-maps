package nl.r1a.grassmap.plugins.way.alerts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.RegionAccess
import nl.r1a.grassmap.plugin.api.model.WayAlertFeature
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmap.plugin.api.model.WayAlertHit
import nl.r1a.grassmap.plugin.api.model.WayAlertKey
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile

/**
 * Where the way alerts are placed, and how wide the route's corridor is
 * (T-0319). One table, so a distance and the reason for it cannot drift apart.
 *
 * The corridor is 30 m. A route line and the OSM geometry of the feature both
 * carry metre-level noise, and a feature a few metres off the carriageway
 * (a barrier at the verge, a hazard marker) still matters. Thirty metres also
 * keeps a parallel railway line or a service road one street away out of the
 * result. The feature must be close enough to the route to matter.
 *
 * The distances are about 20 to 40 seconds at the profile's nominal speed, so
 * the rider hears the alert before the feature is visible and has time to act:
 *
 * | Feature | Hike (5 km/h) | Bike (18 km/h) |
 * |---|---|---|
 * | Railway crossing | 50 m, 36 s | 150 m, 30 s |
 * | Tunnel | - | 200 m, 40 s |
 * | Hazard, ford, barrier | 30 m, 22 s | 100 m, 20 s |
 *
 * Walking and driving get no way alerts: the product scope gives railway
 * crossings and hazards to bike and hike, and tunnels to bike only. A profile
 * with no distance returns null and the schedule skips the feature.
 */
object WayAlertDistance {
    const val CORRIDOR_METERS: Double = 30.0

    fun metersFor(
        profile: RouteProfile,
        kind: WayAlertFeatureKind,
    ): Double? =
        when (profile) {
            RouteProfile.Bike -> bikeMeters(kind)
            RouteProfile.Hike -> hikeMeters(kind)
            RouteProfile.Walk, RouteProfile.Car -> null
        }

    private fun bikeMeters(kind: WayAlertFeatureKind): Double =
        when (kind) {
            WayAlertFeatureKind.RailwayCrossing -> 150.0
            WayAlertFeatureKind.Tunnel -> 200.0
            WayAlertFeatureKind.Hazard, WayAlertFeatureKind.Ford, WayAlertFeatureKind.Barrier -> 100.0
        }

    private fun hikeMeters(kind: WayAlertFeatureKind): Double? =
        when (kind) {
            WayAlertFeatureKind.RailwayCrossing -> 50.0
            WayAlertFeatureKind.Tunnel -> null
            WayAlertFeatureKind.Hazard, WayAlertFeatureKind.Ford, WayAlertFeatureKind.Barrier -> 30.0
        }
}

/** The guidance phases a way-alert schedule reads, in its own terms. */
enum class WayAlertPhase {
    Idle,
    Following,
    Rerouting,
    Paused,
    Arrived,
    Stopped,
}

/**
 * One published state, mapped from the navigation session by the app. The
 * plugin module does not import `navigation`; [alongRouteMeters] is the route
 * total minus the remaining distance, and [route] is the active route.
 */
data class WayAlertState(
    val phase: WayAlertPhase = WayAlertPhase.Idle,
    val routeRevision: Long = 0L,
    val route: Route? = null,
    val alongRouteMeters: Double = 0.0,
    val fixTimeMillis: Long? = null,
)

/** One feature the schedule decided to announce, with the distance that decided it. */
data class WayAlertCue(
    val feature: WayAlertFeature,
    val key: WayAlertKey,
    val distanceAheadMeters: Double,
    val fixTimeMillis: Long,
)

/** Where a host sends each due cue; the app bridges it to the alert host. */
fun interface WayAlertSink {
    fun emit(cue: WayAlertCue)
}

/** The sink that does nothing, used until the app wires a real one. */
object NoopWayAlertSink : WayAlertSink {
    override fun emit(cue: WayAlertCue) = Unit
}

/**
 * The way-alert policy for one navigation state stream (T-0319).
 *
 * On a new route revision the schedule asks [RegionAccess] for the region
 * features inside [WayAlertDistance.CORRIDOR_METERS] of the route, then caches
 * them. A feature is announced on the first state that puts it ahead of the
 * rider at or inside the distance for the active profile. Unlike a turn beep,
 * a feature first seen inside the distance is announced at once: it is a
 * hazard on the road, not a maneuver the rider can anticipate.
 *
 * The once-per-feature key is [WayAlertKey]: the route revision plus the OSM
 * element type and id. A feature is announced once per revision; a reroute
 * changes the revision and re-arms every feature on the replacement route.
 * The announced set is cleared on that revision change, and on a terminal
 * phase, so a new trip starts clean even when its revision starts at zero.
 *
 * A paused phase keeps the cache and the announced set but emits nothing. A
 * feature that is behind the rider at pause does not announce on resume; one
 * still ahead and not yet announced does.
 */
class WayAlertSchedule(
    private val region: RegionAccess,
    private val log: (String) -> Unit = {},
) {
    /** The profile whose distances are in use; the app sets it on start. */
    @Volatile
    var profile: RouteProfile = RouteProfile.Walk
        private set

    private var loadedRevision: Long? = null
    private var hits: List<WayAlertHit> = emptyList()
    private val announced = mutableSetOf<WayAlertKey>()
    private val mutex = Mutex()

    /** Changes the profile; the next state reads the new distances. */
    fun setProfile(profile: RouteProfile) {
        this.profile = profile
    }

    /** Forgets the route and every announcement, for a new session. */
    suspend fun reset() {
        mutex.withLock { clear() }
    }

    private fun clear() {
        loadedRevision = null
        hits = emptyList()
        announced.clear()
    }

    /** Reads one state and returns every cue that is due. */
    suspend fun onState(state: WayAlertState): List<WayAlertCue> = mutex.withLock { evaluate(state) }

    private suspend fun evaluate(state: WayAlertState): List<WayAlertCue> {
        when (state.phase) {
            WayAlertPhase.Idle, WayAlertPhase.Arrived, WayAlertPhase.Stopped -> {
                clear()
                return emptyList()
            }

            WayAlertPhase.Paused -> {
                return emptyList()
            }

            WayAlertPhase.Following, WayAlertPhase.Rerouting -> {
            }
        }
        val route = state.route ?: return emptyList()
        val fixTimeMillis = state.fixTimeMillis ?: return emptyList()
        if (!state.alongRouteMeters.isFinite()) {
            return emptyList()
        }
        ensureHits(state.routeRevision, route)

        val cues = mutableListOf<WayAlertCue>()
        for (hit in hits) {
            val threshold = WayAlertDistance.metersFor(profile, hit.feature.kind) ?: continue
            val ahead = hit.alongRouteMeters - state.alongRouteMeters
            if (!ahead.isFinite() || ahead < 0.0 || ahead > threshold) {
                continue
            }
            val key = WayAlertKey(state.routeRevision, hit.feature.elementType, hit.feature.osmId)
            if (announced.add(key)) {
                cues +=
                    WayAlertCue(
                        feature = hit.feature,
                        key = key,
                        distanceAheadMeters = ahead,
                        fixTimeMillis = fixTimeMillis,
                    )
            }
        }
        return cues
    }

    private suspend fun ensureHits(
        revision: Long,
        route: Route,
    ) {
        if (loadedRevision == revision) {
            return
        }
        loadedRevision = revision
        announced.clear()
        when (val outcome = region.wayAlertFeatures(route, WayAlertDistance.CORRIDOR_METERS)) {
            is Outcome.Failure -> {
                hits = emptyList()
                log("way-alert query failed: ${outcome.error}")
            }

            is Outcome.Success -> {
                hits = outcome.value
                log("way-alert features=${hits.size} revision=$revision corridor_m=${WayAlertDistance.CORRIDOR_METERS}")
            }
        }
    }
}

/**
 * Feeds published states into [WayAlertSchedule] and sends each due cue to a
 * [WayAlertSink]. It owns no clock and no Android type, so a JVM test drives
 * it directly.
 */
class WayAlertHost(
    private val schedule: WayAlertSchedule,
    private val sink: WayAlertSink = NoopWayAlertSink,
) {
    constructor(
        region: RegionAccess,
        sink: WayAlertSink = NoopWayAlertSink,
        log: (String) -> Unit = {},
    ) : this(WayAlertSchedule(region, log), sink)

    /** The profile whose distances are in use. */
    val profile: RouteProfile
        get() = schedule.profile

    /** Sets the profile on a new session, like `core.nav.start` does for beeps. */
    fun setProfile(profile: RouteProfile) {
        schedule.setProfile(profile)
    }

    /** Forgets the route and every announcement, for a new session. */
    suspend fun reset() {
        schedule.reset()
    }

    /** Reads one state and sends every due cue to the sink. */
    suspend fun onState(state: WayAlertState) {
        schedule.onState(state).forEach(sink::emit)
    }
}
