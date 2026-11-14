@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package nl.r1a.grassmap.plugins.proximity.alerts

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import nl.r1a.grassmap.alerts.AlertEffect
import nl.r1a.grassmap.alerts.AlertEffectSink
import nl.r1a.grassmap.alerts.AlertEvent
import nl.r1a.grassmap.alerts.AlertHost
import nl.r1a.grassmap.alerts.AlertKind
import nl.r1a.grassmap.alerts.AlertSettings
import nl.r1a.grassmap.navigation.GeometryRouteFollower
import nl.r1a.grassmap.navigation.NavigationConfig
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationSession
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.navigation.PolylineLocator
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.ProximityPlaceKind
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import kotlin.math.cos

/** The effects the host sent, in order. */
internal class RecordingEffectSink : AlertEffectSink {
    val effects = mutableListOf<AlertEffect>()

    override fun emit(effect: AlertEffect) {
        effects += effect
    }
}

/**
 * The router the replay supplies. A followed track is replayed with
 * `reroute = false`, so the session never asks for a route; a request that
 * arrives is still a typed failure rather than a crash.
 */
internal object UnusedRoutingEngine : RoutingEngine {
    override val contract: ProviderContract = RoutingEngineContract.REFERENCE

    override fun capabilities(): RoutingCapabilities =
        RoutingCapabilities(
            profiles = setOf(RouteProfile.Walk),
            modifierKinds = emptySet(),
            maxAlternatives = 0,
            maxDistanceMeters = 250_000.0,
        )

    override suspend fun route(
        request: RouteRequest,
        cancellation: CancellationHandle,
    ): Outcome<List<Route>> = Outcome.Failure(AppError.Unsupported)
}

/** One replayed trip: the alerts it raised, the effects it sent and its states. */
internal data class ProximityReplay(
    val alerts: List<AlertEvent>,
    val effects: List<AlertEffect>,
    val states: List<NavigationState>,
) {
    val kinds: List<AlertKind>
        get() = alerts.map { it.kind }
}

/**
 * The T-0412 replay harness, in the shape the proximity acceptance needs: one
 * deterministic line, one fix every [FIX_STEP_METERS] at
 * [FIX_INTERVAL_MILLIS], replayed through the production [NavigationSession]
 * with `reroute = false`, as a followed track is.
 *
 * Each published state is fed to the proximity schedule, whose cue the sink
 * bridges into the real [AlertHost] exactly as the app does. The session runs
 * the unambiguous `emit` -> process path, so the state read after an `emit` is
 * the state for that fix.
 */
internal suspend fun TestScope.replayProximity(
    route: Route,
    alongs: List<Double>,
    settings: AlertSettings,
    places: List<ProximityPlace> = emptyList(),
    poiKinds: Set<String> = emptySet(),
): ProximityReplay {
    val effectSink = RecordingEffectSink()
    val alerts = AlertHost(settings, effectSink)
    val events = mutableListOf<AlertEvent>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
        alerts.events.collect { events += it }
    }
    val proximity =
        ProximityAlertHost(
            source = { _, _ -> ProximityPlaces(places) },
            poiKinds = { poiKinds },
            sink = { cue ->
                alerts.onPlaceAlert(
                    placeKind = cue.place.kind,
                    fixTimeMillis = cue.fixTimeMillis,
                    distanceMeters = cue.distanceMeters,
                    placeName = cue.place.name,
                )
            },
        )
    val session =
        NavigationSession(
            follower = GeometryRouteFollower(),
            routing = UnusedRoutingEngine,
            profile = RouteProfile.Walk,
            config = NavigationConfig(reroute = false),
        )
    val states = mutableListOf<NavigationState>()
    val fixes =
        flow {
            for (fix in fixesAlong(route, alongs)) {
                emit(fix)
                val state = session.state.value
                states += state
                proximity.onState(state.toProximityState())
            }
        }
    when (val run = session.run(route, fixes)) {
        is Outcome.Success -> Unit
        is Outcome.Failure -> throw AssertionError("the replay failed: ${run.error}")
    }
    return ProximityReplay(events, effectSink.effects, states)
}

/**
 * The app's `NavigationState.toProximityState()` in four lines, kept beside the
 * replay because the plugin module cannot see `:app`. `ProximityAlertWiringTest`
 * holds the app's own mapping to the same rules.
 */
internal fun NavigationState.toProximityState(): ProximityState =
    ProximityState(
        active = phase != NavigationPhase.Idle && phase != NavigationPhase.Stopped,
        fixTimeMillis = fix?.timeMillis,
        position = fix?.position,
    )

/** A straight route [eastMeters] long from the Lviv origin, in one segment. */
internal fun lineRoute(eastMeters: Double): Route {
    val start = GeoPoint(ORIGIN_LATITUDE, ORIGIN_LONGITUDE)
    val end = GeoPoint(ORIGIN_LATITUDE, ORIGIN_LONGITUDE + eastMeters / METERS_PER_DEGREE_LONGITUDE)
    val line = listOf(start, end)
    val distance = PolylineLocator(line).totalMeters()
    return Route(
        geometry = line,
        distanceMeters = distance,
        durationSeconds = distance / WALKING_SPEED_METERS_PER_SECOND,
    )
}

/** The point [alongMeters] along [route], on its line. */
internal fun pointAlong(
    route: Route,
    alongMeters: Double,
): GeoPoint = PolylineLocator(route.geometry).pointAt(alongMeters) ?: route.geometry.last()

/** A place on the line, [alongMeters] from the start. */
internal fun placeOnLine(
    route: Route,
    kind: ProximityPlaceKind,
    alongMeters: Double,
    name: String,
    id: String = "$name@${alongMeters.toInt()}",
    poiKind: String? = null,
): ProximityPlace =
    ProximityPlace(
        kind = kind,
        id = id,
        position = pointAlong(route, alongMeters),
        name = name,
        poiKind = poiKind,
    )

/** One fix per along-distance, each [FIX_STEP_METERS] of the line apart in time. */
internal fun fixesAlong(
    route: Route,
    alongs: List<Double>,
): List<LocationFix> =
    alongs.mapIndexed { index, along ->
        LocationFix(
            position = pointAlong(route, along),
            accuracyMeters = FIX_ACCURACY_METERS,
            timeMillis = BASE_MILLIS + index * FIX_INTERVAL_MILLIS,
        )
    }

/** Every step of [from] to [to], both ends included, ascending or descending. */
internal fun steps(
    from: Double,
    to: Double,
    step: Double,
): List<Double> {
    require(step != 0.0) { "a step must move" }
    val values = mutableListOf<Double>()
    var value = from
    while (if (step > 0) value <= to else value >= to) {
        values += value
        value += step
    }
    return values
}

internal const val BASE_MILLIS: Long = 1_700_000_000_000L
internal const val FIX_STEP_METERS: Double = 10.0
internal const val FIX_INTERVAL_MILLIS: Long = 10_000L
internal const val FIX_ACCURACY_METERS: Double = 5.0
internal const val ORIGIN_LATITUDE: Double = 49.84
internal const val ORIGIN_LONGITUDE: Double = 24.03

private const val WALKING_SPEED_METERS_PER_SECOND = 1.1
private const val METERS_PER_DEGREE_LATITUDE = 111_320.0
private val METERS_PER_DEGREE_LONGITUDE = METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(ORIGIN_LATITUDE))
