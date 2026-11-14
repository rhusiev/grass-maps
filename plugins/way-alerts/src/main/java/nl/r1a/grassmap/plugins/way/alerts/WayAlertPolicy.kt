package nl.r1a.grassmap.plugins.way.alerts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.RegionAccess
import nl.r1a.grassmap.plugin.api.model.WayAlertElementType
import nl.r1a.grassmap.plugin.api.model.WayAlertFeature
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmap.plugin.api.model.WayAlertHit
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

    /**
     * How far beyond a feature's alert distance its latch is released.
     *
     * Chosen, not canon: the same 50 m as the proximity schedule's
     * `ProximityRadius.RELEASE_METERS`, so both alert kinds call the traveller
     * away at the same distance. The measure here is the along-route offset: a
     * feature releases when it is this far behind the rider, or this far beyond
     * its alert distance ahead of them. Without the margin a fix that wobbles
     * at the boundary would announce twice for one approach.
     */
    const val RELEASE_METERS: Double = 50.0

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
    val distanceAheadMeters: Double,
    val fixTimeMillis: Long,
)

/** The identity of one feature for the approach latch: its OSM element. */
private data class FeatureKey(
    val elementType: WayAlertElementType,
    val osmId: Long,
)

private fun WayAlertFeature.key(): FeatureKey = FeatureKey(elementType, osmId)

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
 * "Once" is one approach, not one revision. The latch is keyed by the
 * feature's own identity, the OSM element type and id, so a reroute keeps every
 * latch: a crossing announced 150 m ahead is not announced again when the
 * replacement route still passes it. A latch releases only when the feature is
 * more than [WayAlertDistance.RELEASE_METERS] beyond its alert distance, which
 * is either behind the rider or that far beyond the distance ahead. A route
 * that leaves the feature and comes back therefore announces it again, once,
 * through the hysteresis rather than through a reroute case. A feature that the
 * replacement route does not pass at all loses its latch with that route,
 * because the schedule can no longer measure a distance to it.
 *
 * A terminal phase and [reset] clear every latch, so a new trip starts clean
 * even when its route revision starts at zero. A paused phase keeps the cache
 * and the latches but emits nothing: a feature that is behind the rider at
 * pause does not announce on resume, while one still ahead and not yet
 * announced does.
 *
 * A feature already inside its alert distance at trip start does announce. A
 * proximity place inside its radius at trip start is latched silently, because
 * the traveller is standing at it. A way alert has no such case: its distance
 * is ahead on the route the trip has just begun, so the feature is being
 * approached, and a hazard the rider starts next to is the one that matters.
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
    private val latched = mutableSetOf<FeatureKey>()
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
        latched.clear()
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
            if (!ahead.isFinite()) {
                continue
            }
            val key = hit.feature.key()
            if (ahead < -WayAlertDistance.RELEASE_METERS ||
                ahead > threshold + WayAlertDistance.RELEASE_METERS
            ) {
                latched.remove(key)
                continue
            }
            if (ahead < 0.0 || ahead > threshold) {
                continue
            }
            if (latched.add(key)) {
                cues +=
                    WayAlertCue(
                        feature = hit.feature,
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
        when (val outcome = region.wayAlertFeatures(route, WayAlertDistance.CORRIDOR_METERS)) {
            is Outcome.Failure -> {
                hits = emptyList()
                log("way-alert query failed: ${outcome.error}")
            }

            is Outcome.Success -> {
                hits = outcome.value
                latched.retainAll(hits.map { it.feature.key() }.toSet())
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
