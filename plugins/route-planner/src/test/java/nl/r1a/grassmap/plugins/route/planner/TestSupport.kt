package nl.r1a.grassmap.plugins.route.planner

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.host.NoRouteHistoryAccess
import nl.r1a.grassmap.plugin.api.host.PluginServices
import nl.r1a.grassmap.plugin.api.host.RouteHistoryAccess
import nl.r1a.grassmap.plugin.api.host.RoutingEngineAccess
import nl.r1a.grassmap.plugin.api.host.StreamAccess
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.RouteHistoryEntry
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.provider.Provider
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.LocationService
import nl.r1a.grassmap.plugin.api.widget.WidgetDescriptor

internal class RecordingContext(
    override val services: PluginServices,
) : PluginContext {
    override val pluginId: PluginId = PluginId(RoutePlannerContract.PLUGIN_ID)

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

    @Suppress("UNCHECKED_CAST")
    fun state(): StateFlow<RoutePlanState> = streams.first { it.id == RoutePlannerContract.stateStream }.flow as StateFlow<RoutePlanState>

    @Suppress("UNCHECKED_CAST")
    fun history(): StateFlow<List<RouteHistoryEntry>> =
        streams.first { it.id == RoutePlannerContract.historyStream }.flow as StateFlow<List<RouteHistoryEntry>>
}

internal class FakeServices(
    override val routingEngines: RoutingEngineAccess,
    override val clock: Clock = Clock { FakeEngine.NOW_MILLIS },
    override val routeHistory: RouteHistoryAccess = NoRouteHistoryAccess,
) : PluginServices {
    override val streams: StreamAccess =
        object : StreamAccess {
            override fun <T : Any> subscribe(streamId: StreamId): Flow<T> = emptyFlow()
        }

    override val location: LocationService =
        object : LocationService {
            override val fixes: Flow<LocationFix?> = emptyFlow()

            override fun hasPermission(): Boolean = false
        }

    override val elevation: ElevationAccess = ElevationAccess { Outcome.Success(null) }

    override val camera: Flow<Camera> = emptyFlow()
}

internal class FakeEngine(
    private val maxAlternatives: Int = 2,
) : RoutingEngine {
    val requests = mutableListOf<RouteRequest>()

    override val contract: ProviderContract = RoutingEngineContract.REFERENCE

    override fun capabilities(): RoutingCapabilities =
        RoutingCapabilities(
            profiles = setOf(RouteProfile.Walk),
            modifierKinds = emptySet(),
            maxAlternatives = maxAlternatives,
            maxDistanceMeters = 250_000.0,
        )

    override suspend fun route(
        request: RouteRequest,
        cancellation: CancellationHandle,
    ): Outcome<List<Route>> {
        requests += request
        val forward = request.waypoints.first() == FORWARD_START
        val routes = if (forward) FORWARD else REVERSE
        val count = 1 + request.alternatives.coerceIn(0, maxAlternatives)
        return Outcome.Success(routes.take(count))
    }

    companion object {
        const val NOW_MILLIS = 1_700_000_000_000L
        val FORWARD_START = GeoPoint(49.8290, 23.9520)
        val FORWARD_END = GeoPoint(49.8420, 24.0320)
        val FORWARD =
            listOf(
                Route(
                    geometry = listOf(FORWARD_START, FORWARD_END),
                    distanceMeters = 1_000.0,
                    durationSeconds = 600.0,
                ),
                Route(
                    geometry = listOf(FORWARD_START, GeoPoint(49.8350, 24.0100), FORWARD_END),
                    distanceMeters = 1_200.0,
                    durationSeconds = 700.0,
                ),
            )
        val REVERSE =
            listOf(
                Route(
                    geometry = listOf(FORWARD_END, FORWARD_START),
                    distanceMeters = 980.0,
                    durationSeconds = 590.0,
                ),
                Route(
                    geometry = listOf(FORWARD_END, GeoPoint(49.8330, 24.0150), FORWARD_START),
                    distanceMeters = 1_150.0,
                    durationSeconds = 680.0,
                ),
            )
    }
}
