package nl.r1a.grassmap.plugins.proximity.alerts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.ProximityPlaceKind

/**
 * One place a proximity alert can announce.
 *
 * [id] is the place's stable identity inside its category: a favourite's id, a
 * track id plus the waypoint's index, or the search index's place id. It is
 * what makes "once per approach" survive a new lookup. [poiKind] is the exact
 * index kind ("amenity=cafe") for [ProximityPlaceKind.Poi] and null for the
 * other two categories. [name] is the place's own stored name, which is data.
 */
data class ProximityPlace(
    val kind: ProximityPlaceKind,
    val id: String,
    val position: GeoPoint,
    val name: String? = null,
    val poiKind: String? = null,
) {
    init {
        require(id.isNotBlank()) { "a place needs an id" }
        require(position.latitude.isFinite() && position.longitude.isFinite()) {
            "the place coordinates must be finite"
        }
    }
}

/** One lookup's places, in the order the source produced them. */
data class ProximityPlaces(
    val places: List<ProximityPlace> = emptyList(),
)

/**
 * Where a host gets the places near the traveller: the app reads the
 * favourites library, the stored tracks' waypoints and the search index, and
 * returns only the place kinds the user picked.
 */
fun interface ProximityPlaceSource {
    suspend fun places(
        box: BoundingBox,
        poiKinds: Set<String>,
    ): ProximityPlaces
}

/** The source a host uses before the app wires one. */
object NoProximityPlaces : ProximityPlaceSource {
    override suspend fun places(
        box: BoundingBox,
        poiKinds: Set<String>,
    ): ProximityPlaces = ProximityPlaces()
}

/**
 * One published session state, in the schedule's own terms. The plugin module
 * does not import `navigation`; the app maps its phase and fix here.
 *
 * [active] is true while a session exists, so a paused or arrived session
 * keeps its latches and a finished one clears them. A state without a fix time
 * or a position is evidence of nothing and is skipped.
 */
data class ProximityState(
    val active: Boolean = false,
    val fixTimeMillis: Long? = null,
    val position: GeoPoint? = null,
)

/** One place the schedule decided to announce, with the distance that decided it. */
data class ProximityAlertCue(
    val place: ProximityPlace,
    val distanceMeters: Double,
    val fixTimeMillis: Long,
)

/** Where a host sends each due cue; the app bridges it to the alert host. */
fun interface ProximityAlertSink {
    fun emit(cue: ProximityAlertCue)
}

/** The sink that does nothing, used until the app wires a real one. */
object NoopProximityAlertSink : ProximityAlertSink {
    override fun emit(cue: ProximityAlertCue) = Unit
}

/** The identity of one place inside its category, for the approach latch. */
private data class PlaceKey(
    val kind: ProximityPlaceKind,
    val id: String,
)

private fun ProximityPlace.key(): PlaceKey = PlaceKey(kind, id)

/**
 * The proximity-alert policy for one navigation state stream (T-0413).
 *
 * A place is announced on the first state that puts the traveller inside its
 * category's radius, and not again until the traveller is at least
 * [ProximityRadius.RELEASE_METERS] beyond that radius. That is the whole scope
 * of "once": one approach, not one fix, and not a time window. The latch is
 * keyed by the place's category and id, so a new lookup with the same places
 * does not repeat them, and a place that leaves the lookup box and comes back
 * is a new approach.
 *
 * A place of an unchosen place kind still takes its latch, exactly like a
 * category whose switch is off in the alert host: choosing a kind does not
 * replay a place the traveller is already next to.
 *
 * The schedule asks [ProximityPlaceSource] for places when the session starts
 * and every [ProximityRadius.REFRESH_METERS] of travel. A failed lookup is the
 * source's to report; it returns the places it does have, and an empty result
 * means no place is due rather than an error.
 *
 * [canAlert] is the caller's answer to "can any place alert be raised right
 * now". The app answers it from the alert host's switches and the chosen place
 * kinds, so a session whose three place switches are off does no lookup at all:
 * a place alert needs its switch on, and looking for places nothing can
 * announce would spend every session's work on an alert that is dropped. While
 * the gate is closed the schedule keeps no places and no latches. Opening it
 * mid-session is therefore a fresh start: the next lookup announces a place the
 * traveller is already inside, because no lookup ran while it was closed.
 */
class ProximityAlertSchedule(
    private val source: ProximityPlaceSource = NoProximityPlaces,
    private val poiKinds: () -> Set<String> = { emptySet() },
    private val canAlert: () -> Boolean,
    private val log: (String) -> Unit = {},
) {
    private var places: List<ProximityPlace> = emptyList()
    private var anchor: GeoPoint? = null
    private var loaded: Boolean = false
    private val announced = mutableSetOf<PlaceKey>()
    private val mutex = Mutex()

    /** Forgets the places and every announcement, for a new session. */
    suspend fun reset() {
        mutex.withLock { clear() }
    }

    /** Reads one state and returns every cue that is due. */
    suspend fun onState(state: ProximityState): List<ProximityAlertCue> = mutex.withLock { evaluate(state) }

    private fun clear() {
        places = emptyList()
        anchor = null
        loaded = false
        announced.clear()
    }

    private suspend fun evaluate(state: ProximityState): List<ProximityAlertCue> {
        if (!state.active || !canAlert()) {
            clear()
            return emptyList()
        }
        val fixTimeMillis = state.fixTimeMillis ?: return emptyList()
        val position = state.position ?: return emptyList()
        if (!position.latitude.isFinite() || !position.longitude.isFinite()) {
            return emptyList()
        }
        refresh(position)
        return due(position, fixTimeMillis)
    }

    private suspend fun refresh(position: GeoPoint) {
        val previous = anchor
        if (previous != null && loaded &&
            ProximityDistance.meters(previous, position) < ProximityRadius.REFRESH_METERS
        ) {
            return
        }
        anchor = position
        loaded = true
        val retrieved = source.places(ProximityBox.around(position, ProximityRadius.QUERY_METERS), poiKinds()).places
        places = retrieved
        announced.retainAll(retrieved.map { it.key() }.toSet())
        log("proximity places=${retrieved.size} box_m=${ProximityRadius.QUERY_METERS.toInt()}")
    }

    private fun due(
        position: GeoPoint,
        fixTimeMillis: Long,
    ): List<ProximityAlertCue> {
        val chosen = poiKinds()
        val cues = mutableListOf<ProximityAlertCue>()
        for (place in places) {
            val distance = ProximityDistance.meters(position, place.position)
            if (!distance.isFinite()) {
                continue
            }
            val radius = ProximityRadius.metersFor(place.kind)
            when {
                distance > radius + ProximityRadius.RELEASE_METERS -> {
                    announced.remove(place.key())
                }

                distance <= radius -> {
                    val firstOfThisApproach = announced.add(place.key())
                    if (firstOfThisApproach && isChosen(place, chosen)) {
                        cues +=
                            ProximityAlertCue(
                                place = place,
                                distanceMeters = distance,
                                fixTimeMillis = fixTimeMillis,
                            )
                    }
                }
            }
        }
        return cues
    }

    private fun isChosen(
        place: ProximityPlace,
        chosen: Set<String>,
    ): Boolean = place.kind != ProximityPlaceKind.Poi || place.poiKind in chosen
}

/**
 * Feeds published states into [ProximityAlertSchedule] and sends each due cue
 * to a [ProximityAlertSink]. It owns no clock and no Android type, so a JVM
 * test drives it directly.
 */
class ProximityAlertHost(
    private val schedule: ProximityAlertSchedule,
    private val sink: ProximityAlertSink = NoopProximityAlertSink,
) {
    constructor(
        source: ProximityPlaceSource,
        poiKinds: () -> Set<String> = { emptySet() },
        canAlert: () -> Boolean,
        sink: ProximityAlertSink = NoopProximityAlertSink,
        log: (String) -> Unit = {},
    ) : this(ProximityAlertSchedule(source, poiKinds, canAlert, log), sink)

    /** Forgets the places and every announcement, for a new session. */
    suspend fun reset() {
        schedule.reset()
    }

    /** Reads one state and sends every due cue to the sink. */
    suspend fun onState(state: ProximityState) {
        schedule.onState(state).forEach(sink::emit)
    }
}
