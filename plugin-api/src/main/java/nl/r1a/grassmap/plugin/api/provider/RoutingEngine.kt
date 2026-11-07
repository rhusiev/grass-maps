package nl.r1a.grassmap.plugin.api.provider

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
}

object RoutingEngineContract {
    const val NAME: String = "routing.engine"
    const val VERSION: String = "1.0.0"

    val REFERENCE: ProviderContract = ProviderContract(NAME, VERSION)
}

/** The travel modes v1 needs. T-0301 adds the remaining profiles. */
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
 * One route request. [waypoints] are ordered breaks. [alternatives] counts the
 * extra routes beyond the primary one. [modifiers] are engine-neutral effects
 * translated by the engine; a kind outside [RoutingCapabilities.modifierKinds]
 * is invalid input.
 */
data class RouteRequest(
    val waypoints: List<GeoPoint>,
    val profile: RouteProfile,
    val modifiers: List<RouteModifier> = emptyList(),
    val alternatives: Int = 0,
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
 * v1 leaves the attributes empty and T-0301 fills them. [rawResponse] keeps the
 * engine response for debugging and is never shown to the user.
 */
data class Route(
    val geometry: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val segments: List<RouteSegment> = emptyList(),
    val instructions: List<RouteInstruction> = emptyList(),
    val rawResponse: String? = null,
)

/** One leg of [Route], with half-open shape indices. */
data class RouteSegment(
    val startIndex: Int,
    val endIndex: Int,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val attributes: Map<String, AttributeValue> = emptyMap(),
)

/** One guidance maneuver, placed by its distance from the route start. */
data class RouteInstruction(
    val text: String,
    val streetName: String? = null,
    val distanceAlongRouteMeters: Double,
    val durationSeconds: Double,
    val maneuver: String? = null,
)
