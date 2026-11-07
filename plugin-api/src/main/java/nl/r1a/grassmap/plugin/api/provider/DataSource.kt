package nl.r1a.grassmap.plugin.api.provider

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.CursorToken
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.SlottedLayer

/**
 * A source of way attributes and routing cost modifiers. One source feeds both
 * the map and the router, keyed by OSM way id and region build, so a stale
 * build is never applied.
 */
interface DataSource : Provider {
    /** The attribute kinds, regions and refresh policy this source serves. */
    fun describe(): DataSourceDescription

    /**
     * One page of way attributes inside [AttributeRequest.area].
     *
     * A limit outside the source's accepted range, or a cursor this source did
     * not issue, fails with [AppError.InvalidInput]. An empty page ends the
     * traversal and carries a null cursor.
     */
    suspend fun attributes(
        request: AttributeRequest,
        cancellation: CancellationHandle,
    ): Outcome<AttributePage>

    /**
     * Engine-neutral cost modifiers for [CostModifierRequest.waypoints]. Each
     * carries the way geometry because the v1 engines take line shapes, not way
     * ids. The caller never sees a malformed modifier: the boundary rejects it.
     */
    suspend fun costModifiers(
        request: CostModifierRequest,
        cancellation: CancellationHandle,
    ): Outcome<List<CostModifier>>

    /** The layer this source draws, or null when attributes stay off the map. */
    fun mapLayer(): SlottedLayer?
}

object DataSourceContract {
    const val NAME: String = "data.source"
    const val VERSION: String = "1.0.0"

    val REFERENCE: ProviderContract = ProviderContract(NAME, VERSION)
}

/**
 * What a source declares before it is asked for data, so the host can choose
 * between sources without activating them.
 */
data class DataSourceDescription(
    val attributeKinds: Set<String>,
    val regions: List<String>,
    val refreshPolicy: String,
)

/**
 * One page request over [area]. [limit] must be at least 1 and no more than the
 * source's declared maximum. A null [cursor] starts at the first page; a token
 * is valid only for the same query owner.
 */
data class AttributeRequest(
    val area: BoundingBox,
    val timeMillis: Long,
    val limit: Int,
    val cursor: CursorToken? = null,
)

/** One page of [WayAttribute] items in the source's stable order. */
data class AttributePage(
    val items: List<WayAttribute>,
    val nextCursor: CursorToken? = null,
)

enum class TravelDirection {
    Forward,
    Backward,
    Both,
}

/**
 * One attribute set for an OSM way. [validFromMillis] and [validToMillis] are
 * open when null. [regionBuild] ties the values to the region package version;
 * a mismatch is stale and never applied.
 */
data class WayAttribute(
    val osmWayId: Long,
    val direction: TravelDirection,
    val validFromMillis: Long?,
    val validToMillis: Long?,
    val regionBuild: String,
    val values: Map<String, AttributeValue>,
)

/**
 * A route to evaluate. [profile] and [regionBuild] are hints: a source that
 * cannot serve the requested build returns no modifiers rather than stale ones.
 */
data class CostModifierRequest(
    val waypoints: List<GeoPoint>,
    val profile: String? = null,
    val regionBuild: String? = null,
)

/**
 * One engine-neutral cost effect on a way. Exactly one of [multiplier],
 * [addedSeconds] or [forbidden] is set; [geometry] is the line the engine reads.
 */
data class CostModifier(
    val osmWayId: Long,
    val geometry: List<GeoPoint>,
    val direction: TravelDirection,
    val regionBuild: String,
    val multiplier: Double? = null,
    val addedSeconds: Double? = null,
    val forbidden: Boolean = false,
)

/**
 * The shared shape rules for [CostModifier]. A provider validates its own
 * output with [violationOf] before returning it, so a malformed modifier never
 * crosses the boundary.
 */
object CostModifierRules {
    /** The rule [modifier] breaks, or null when it is well formed. */
    fun violationOf(modifier: CostModifier): AppError? {
        if (modifier.osmWayId <= 0 || modifier.geometry.size < MIN_GEOMETRY_POINTS) {
            return AppError.InvalidInput
        }
        if (modifier.regionBuild.isBlank()) {
            return AppError.InvalidInput
        }
        val effects = listOf(modifier.multiplier != null, modifier.addedSeconds != null, modifier.forbidden)
        if (effects.count { it } != 1) {
            return AppError.InvalidInput
        }
        modifier.multiplier?.let { multiplier ->
            if (!multiplier.isFinite() || multiplier <= 0.0) {
                return AppError.InvalidInput
            }
        }
        modifier.addedSeconds?.let { seconds ->
            if (!seconds.isFinite() || seconds < 0.0) {
                return AppError.InvalidInput
            }
        }
        return null
    }

    private const val MIN_GEOMETRY_POINTS = 2
}
