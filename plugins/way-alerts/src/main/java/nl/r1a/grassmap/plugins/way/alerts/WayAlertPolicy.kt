package nl.r1a.grassmaps.plugins.way.alerts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.host.RegionAccess
import nl.r1a.grassmaps.plugin.api.model.WayAlertElementType
import nl.r1a.grassmaps.plugin.api.model.WayAlertFeature
import nl.r1a.grassmaps.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmaps.plugin.api.model.WayAlertHit
import nl.r1a.grassmaps.plugin.api.provider.Route
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile

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
 * | Feature | Walk (4 km/h) | Hike (5 km/h) | Bike (18 km/h) |
 * |---|---|---|---|
 * | Railway crossing | 100 m, then 50 m | 50 m, 36 s | 150 m, 30 s |
 * | Tunnel | - | - | 200 m, 40 s |
 * | Hazard, ford, barrier | - | 30 m, 22 s | 100 m, 20 s |
 *
 * Walking gets the railway crossing only, in two stages (T-0322, round 5 of
 * the navigation design): the first alert at 100 m, the second at 50 m. Hike
 * and bike keep their single distance. Driving gets no way alerts, and walking
 * gets no tunnel or hazard alert: the product scope gives hazards to bike and
 * hike, and tunnels to bike only. A profile with no distance returns null and
 * the schedule skips the feature.
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

    /** The walking railway-crossing stages (T-0322): the first alert, then the second. */
    const val WALK_RAILWAY_FIRST_METERS: Double = 100.0
    const val WALK_RAILWAY_SECOND_METERS: Double = 50.0

    /**
     * The first alert distance for the profile and kind, or null for none. A
     * profile with several stages returns its farthest one; [stagesFor] has all.
     */
    fun metersFor(
        profile: RouteProfile,
        kind: WayAlertFeatureKind,
    ): Double? = stagesFor(profile, kind).firstOrNull()

    /**
     * Every alert distance for the profile and kind, farthest first. Empty when
     * the profile gets no alert for the kind. Each stage announces once per
     * approach.
     */
    fun stagesFor(
        profile: RouteProfile,
        kind: WayAlertFeatureKind,
    ): List<Double> =
        when (profile) {
            RouteProfile.Bike -> listOf(bikeMeters(kind))
            RouteProfile.Hike -> listOfNotNull(hikeMeters(kind))
            RouteProfile.Walk -> walkStages(kind)
            RouteProfile.Car -> emptyList()
        }

    private fun walkStages(kind: WayAlertFeatureKind): List<Double> =
        when (kind) {
            WayAlertFeatureKind.RailwayCrossing -> listOf(WALK_RAILWAY_FIRST_METERS, WALK_RAILWAY_SECOND_METERS)

            WayAlertFeatureKind.Tunnel,
            WayAlertFeatureKind.Hazard,
            WayAlertFeatureKind.Ford,
            WayAlertFeatureKind.Barrier,
            -> emptyList()
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

/** One alert stage of one feature: the latch unit, so each stage announces once. */
private data class StageKey(
    val feature: FeatureKey,
    val stageMeters: Double,
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
 * A profile may have several alert stages for a kind: walking gets a railway
 * crossing at 100 m and again at 50 m (T-0322). Each stage has its own latch,
 * so each announces once per approach. A state that makes several stages due
 * at once, for example the first fix of a trip 40 m from the crossing, sends
 * one cue and latches all of them, so the walker is not told twice in a row. A
 * feature's latches release together, beyond its farthest stage.
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
    private val latched = mutableSetOf<StageKey>()
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
            val stages = WayAlertDistance.stagesFor(profile, hit.feature.kind)
            val farthest = stages.firstOrNull() ?: continue
            val ahead = hit.alongRouteMeters - state.alongRouteMeters
            if (!ahead.isFinite()) {
                continue
            }
            val key = hit.feature.key()
            if (ahead < -WayAlertDistance.RELEASE_METERS ||
                ahead > farthest + WayAlertDistance.RELEASE_METERS
            ) {
                latched.removeAll { it.feature == key }
                continue
            }
            if (ahead < 0.0) {
                continue
            }
            val isNewStageDue = stages.filter { ahead <= it }.map { latched.add(StageKey(key, it)) }.any { it }
            if (isNewStageDue) {
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
                val present = hits.map { it.feature.key() }.toSet()
                latched.retainAll { it.feature in present }
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
