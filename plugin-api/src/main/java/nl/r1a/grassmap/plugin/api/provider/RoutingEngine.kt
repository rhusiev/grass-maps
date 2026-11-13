package nl.r1a.grassmap.plugin.api.provider

import kotlinx.serialization.Serializable
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.GeoPoint

/**
 * A route engine behind the `routing.engine` contract. The contract carries no
 * engine types: an engine translates [RouteRequest] into its own request and
 * its own answer into [Route].
 */
interface RoutingEngine : Provider {
    /** What this engine accepts before a request is built. */
    fun capabilities(): RoutingCapabilities

    /**
     * Routes through [RouteRequest.waypoints] in order. The result has one item
     * per requested alternative; the first item is the primary route. A request
     * outside [capabilities] is invalid input and returns no route.
     */
    suspend fun route(
        request: RouteRequest,
        cancellation: CancellationHandle,
    ): Outcome<List<Route>>

    /**
     * Re-reads [route] with the engine's per-segment attributes, keyed by the
     * route line rather than by a second routing run. The returned route keeps
     * the geometry, distance, duration and instructions of [route], and its
     * segments gain the keys [RoutingCapabilities.attributeKeys] declares.
     *
     * This runs after guidance is already showing, because an engine may have
     * to match the line to its graph edge by edge. An engine without the
     * capability returns the route unchanged; a caller must not wait for it
     * before drawing the route.
     */
    suspend fun annotate(
        route: Route,
        profile: RouteProfile,
        cancellation: CancellationHandle,
    ): Outcome<Route> = Outcome.Success(route)
}

object RoutingEngineContract {
    const val NAME: String = "routing.engine"

    /** 1.3.0 replaces the two-state hilliness with three independent preferences (T-0421). */
    const val VERSION: String = "1.3.0"

    val REFERENCE: ProviderContract = ProviderContract(NAME, VERSION)
}

/**
 * The travel modes the shared model names. An engine declares the ones it
 * accepts in [RoutingCapabilities.profiles]; v1 ships walk, bike and hike, and
 * [Car] is kept for the engine that declares it later.
 */
@Serializable
enum class RouteProfile {
    Walk,
    Bike,
    Hike,
    Car,
}

/** The modifier effects an engine can translate. */
enum class RouteModifierKind {
    Multiplier,
    AddedSeconds,
    Forbidden,
}

/**
 * An engine's static abilities. The host checks a request against this before it
 * calls [RoutingEngine.route], so an unsupported request is refused here rather
 * than half translated by the engine.
 */
data class RoutingCapabilities(
    val profiles: Set<RouteProfile>,
    val modifierKinds: Set<RouteModifierKind>,
    val maxAlternatives: Int,
    val maxDistanceMeters: Double,
    val attributeKeys: Set<String> = emptySet(),
    val hasMatchTrack: Boolean = false,
)

/**
 * The attribute keys a route segment may carry. The vocabulary is shared so an
 * engine and the UI agree on the spelling before [RoutingEngine.annotate] runs.
 */
object RouteAttributes {
    const val SURFACE: String = "surface"
    const val HIGHWAY: String = "highway"
    const val GRADE: String = "grade"
    const val SAC_SCALE: String = "sac_scale"
    const val ACCESS: String = "access"

    /** The five attributes the v1 engine fills. */
    val V1: Set<String> = setOf(SURFACE, HIGHWAY, GRADE, SAC_SCALE, ACCESS)
}

/**
 * One route request. [waypoints] are ordered breaks. [alternatives] counts the
 * extra routes beyond the primary one. [modifiers] are engine-neutral effects
 * translated by the engine; a kind outside [RoutingCapabilities.modifierKinds]
 * is invalid input. [options] are the chosen profile's preferences (T-0308,
 * three steps in T-0421), already read from the per-profile settings or from a
 * one-route override by the caller.
 */
data class RouteRequest(
    val waypoints: List<GeoPoint>,
    val profile: RouteProfile,
    val modifiers: List<RouteModifier> = emptyList(),
    val alternatives: Int = 0,
    val options: RouteOptions = RouteOptions(),
)

/**
 * One engine-neutral cost overlay. Exactly one of [multiplier], [addedSeconds]
 * or [forbidden] is set. [geometry] is the line the engine reads.
 *
 * The direction is carried for engines that can honour it. The v1 Valhalla
 * overlays (`linear_cost_factors` and `exclude_polygons`) apply to both
 * directions, so a one-way modifier is still sent and the limitation is left
 * for T-0301 to close.
 */
data class RouteModifier(
    val geometry: List<GeoPoint>,
    val direction: TravelDirection = TravelDirection.Both,
    val multiplier: Double? = null,
    val addedSeconds: Double? = null,
    val forbidden: Boolean = false,
    val osmWayId: Long? = null,
) {
    val kind: RouteModifierKind?
        get() =
            when {
                multiplier != null -> RouteModifierKind.Multiplier
                addedSeconds != null -> RouteModifierKind.AddedSeconds
                forbidden -> RouteModifierKind.Forbidden
                else -> null
            }

    companion object {
        /** Copies the DataSource values a router can act on. */
        fun fromCostModifier(modifier: CostModifier): RouteModifier =
            RouteModifier(
                geometry = modifier.geometry,
                direction = modifier.direction,
                multiplier = modifier.multiplier,
                addedSeconds = modifier.addedSeconds,
                forbidden = modifier.forbidden,
                osmWayId = modifier.osmWayId,
            )
    }
}

/**
 * The shared shape rule for a [RouteModifier], mirroring
 * [CostModifierRules] so an engine never receives a malformed overlay.
 */
object RouteModifierRules {
    fun violationOf(modifier: RouteModifier): AppError? {
        if (modifier.geometry.size < MIN_GEOMETRY_POINTS) {
            return AppError.InvalidInput
        }
        when (modifier.kind) {
            null -> {
                return AppError.InvalidInput
            }

            RouteModifierKind.Multiplier -> {
                val multiplier = modifier.multiplier ?: return AppError.InvalidInput
                if (!multiplier.isFinite() || multiplier <= 0.0) {
                    return AppError.InvalidInput
                }
            }

            RouteModifierKind.AddedSeconds -> {
                val seconds = modifier.addedSeconds ?: return AppError.InvalidInput
                if (!seconds.isFinite() || seconds < 0.0) {
                    return AppError.InvalidInput
                }
            }

            RouteModifierKind.Forbidden -> {
                Unit
            }
        }
        return null
    }

    private const val MIN_GEOMETRY_POINTS = 2
}

/**
 * A route in the shared model. [geometry] is the full ordered line. [segments]
 * split it by leg and carry attributes under the engine's declared vocabulary;
 * [RoutingEngine.annotate] re-splits them by graph edge and fills the attributes
 * after the route is already returned. [rawResponse] keeps the engine response
 * for debugging and is never shown to the user.
 */
data class Route(
    val geometry: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val segments: List<RouteSegment> = emptyList(),
    val instructions: List<RouteInstruction> = emptyList(),
    val rawResponse: String? = null,
)

/**
 * One leg of [Route], with half-open shape indices. Each leg is one hop between
 * requested waypoints, so its distance and duration are the route's own time
 * model below the whole-route totals.
 */
@Serializable
data class RouteSegment(
    val startIndex: Int,
    val endIndex: Int,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val attributes: Map<String, AttributeValue> = emptyMap(),
)

/** One guidance maneuver, placed by its distance from the route start. */
@Serializable
data class RouteInstruction(
    val text: String,
    val streetName: String? = null,
    val distanceAlongRouteMeters: Double,
    val durationSeconds: Double,
    val maneuver: String? = null,
)
