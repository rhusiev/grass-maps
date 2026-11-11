package nl.r1a.grassmap.plugins.nav.simulator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import nl.r1a.grassmap.navigation.GeometryRouteFollower
import nl.r1a.grassmap.navigation.RouteFollowerEngine
import nl.r1a.grassmap.navigation.RouteFollowerHandle
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.host.PluginServices
import nl.r1a.grassmap.plugin.api.host.StreamAccess
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.RoutePlanOption
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.provider.Provider
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.LocationService
import nl.r1a.grassmap.plugin.api.widget.WidgetDescriptor
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A route with one turn: east along a Lviv street, then south across it. */
internal fun acceptanceRoute(): Route {
    val geometry =
        listOf(
            GeoPoint(49.8419, 24.0316),
            GeoPoint(49.8419, 24.0325),
            GeoPoint(49.8410, 24.0325),
        )
    val first = haversineMeters(geometry[0], geometry[1])
    val second = haversineMeters(geometry[1], geometry[2])
    return Route(
        geometry = geometry,
        distanceMeters = first + second,
        durationSeconds = (first + second) / WALKING_METERS_PER_SECOND,
        segments =
            listOf(
                RouteSegment(1, 2, first, first / WALKING_METERS_PER_SECOND),
                RouteSegment(2, 3, second, second / WALKING_METERS_PER_SECOND),
            ),
    )
}

internal fun routePlanState(route: Route = acceptanceRoute()): RoutePlanState =
    RoutePlanState(
        status = RoutePlanStatus.Ready,
        options =
            listOf(
                RoutePlanOption(
                    geometry = route.geometry,
                    distanceMeters = route.distanceMeters,
                    durationSeconds = route.durationSeconds,
                    arrivalEpochMillis = NavSimulatorContract.START_EPOCH_MILLIS,
                    segments = route.segments,
                    instructions = route.instructions,
                ),
            ),
    )

/** The ground distance between two fixes, with the IUGG mean radius. */
internal fun haversineMeters(
    from: GeoPoint,
    to: GeoPoint,
): Double {
    val fromLat = Math.toRadians(from.latitude)
    val toLat = Math.toRadians(to.latitude)
    val deltaLat = toLat - fromLat
    val deltaLon = Math.toRadians(to.longitude - from.longitude)
    val a = sin(deltaLat / 2) * sin(deltaLat / 2) + cos(fromLat) * cos(toLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/**
 * The distance along [geometry] at which [point] lies. The fixes are on the
 * line, so this is the test's own projection rather than the simulator's
 * arithmetic. A corner point projects onto either segment at the same total.
 */
internal fun alongMeters(
    point: GeoPoint,
    geometry: List<GeoPoint>,
): Double {
    var cumulative = 0.0
    var bestDistance = Double.POSITIVE_INFINITY
    var bestAlong = 0.0
    for (index in 1 until geometry.size) {
        val start = geometry[index - 1]
        val end = geometry[index]
        val segmentMeters = haversineMeters(start, end)
        val fraction = fractionOnSegment(point, start, end)
        val projected =
            GeoPoint(
                latitude = start.latitude + (end.latitude - start.latitude) * fraction,
                longitude = start.longitude + (end.longitude - start.longitude) * fraction,
            )
        val distance = haversineMeters(point, projected)
        if (distance < bestDistance) {
            bestDistance = distance
            bestAlong = cumulative + segmentMeters * fraction
        }
        cumulative += segmentMeters
    }
    return bestAlong
}

private fun fractionOnSegment(
    point: GeoPoint,
    start: GeoPoint,
    end: GeoPoint,
): Double {
    val latitudeScale = cos(Math.toRadians(start.latitude)).coerceAtLeast(MIN_LATITUDE_SCALE)
    val endEast = Math.toRadians(end.longitude - start.longitude) * EARTH_RADIUS_METERS * latitudeScale
    val endNorth = Math.toRadians(end.latitude - start.latitude) * EARTH_RADIUS_METERS
    val pointEast = Math.toRadians(point.longitude - start.longitude) * EARTH_RADIUS_METERS * latitudeScale
    val pointNorth = Math.toRadians(point.latitude - start.latitude) * EARTH_RADIUS_METERS
    val lengthSquared = endEast * endEast + endNorth * endNorth
    if (lengthSquared <= 0.0) {
        return 0.0
    }
    return ((pointEast * endEast + pointNorth * endNorth) / lengthSquared).coerceIn(0.0, 1.0)
}

/** Counts sessions so a second start can prove it did not begin another one. */
internal class CountingFollower(
    private val delegate: RouteFollowerEngine = GeometryRouteFollower(),
) : RouteFollowerEngine {
    var begins: Int = 0
        private set

    override fun begin(route: Route): Outcome<RouteFollowerHandle> {
        begins += 1
        return delegate.begin(route)
    }
}

internal class RecordingContext(
    override val services: PluginServices,
) : PluginContext {
    override val pluginId: PluginId = PluginId(NavSimulatorContract.PLUGIN_ID)

    val commands = mutableListOf<Command<*, *>>()
    val streams = mutableListOf<StreamDescriptor<*>>()
    val providers = mutableListOf<Provider>()
    val widgets = mutableListOf<WidgetDescriptor>()

    override fun addCommand(command: Command<*, *>) {
        commands += command
    }

    override fun addStream(stream: StreamDescriptor<*>) {
        streams += stream
    }

    override fun addProvider(provider: Provider) {
        providers += provider
    }

    override fun addWidget(widget: WidgetDescriptor) {
        widgets += widget
    }

    fun command(id: CommandId): Command<*, *> = commands.first { it.id == id }
}

internal class FakeServices(
    plan: RoutePlanState? = null,
    override val clock: Clock = Clock { NavSimulatorContract.START_EPOCH_MILLIS },
) : PluginServices {
    override val streams: StreamAccess =
        object : StreamAccess {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> subscribe(streamId: StreamId): Flow<T> =
                if (streamId.value == NavSimulatorContract.CURRENT_ROUTE_STREAM_ID && plan != null) {
                    flowOf(plan as T)
                } else {
                    emptyFlow()
                }
        }

    override val location: LocationService =
        object : LocationService {
            override val fixes: Flow<LocationFix?> = emptyFlow()

            override fun hasPermission(): Boolean = false
        }

    override val elevation: ElevationAccess = ElevationAccess { Outcome.Success(null) }

    override val camera: Flow<Camera> = emptyFlow()
}

private const val EARTH_RADIUS_METERS = 6_371_008.8
private const val WALKING_METERS_PER_SECOND = 1.4
private const val MIN_LATITUDE_SCALE = 0.01

internal fun <T> Outcome<T>.valueOrFail(): T =
    when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> throw AssertionError("expected success, got $error")
    }
