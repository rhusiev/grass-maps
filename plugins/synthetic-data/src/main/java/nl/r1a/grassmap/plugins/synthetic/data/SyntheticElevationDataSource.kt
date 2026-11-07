package nl.r1a.grassmap.plugins.synthetic.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.CursorToken
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.provider.AttributePage
import nl.r1a.grassmap.plugin.api.provider.AttributeRequest
import nl.r1a.grassmap.plugin.api.provider.CostModifier
import nl.r1a.grassmap.plugin.api.provider.CostModifierRequest
import nl.r1a.grassmap.plugin.api.provider.CostModifierRules
import nl.r1a.grassmap.plugin.api.provider.DataSource
import nl.r1a.grassmap.plugin.api.provider.DataSourceContract
import nl.r1a.grassmap.plugin.api.provider.DataSourceDescription
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.WayAttribute

/**
 * A deterministic elevation source over the fixture-mini bbox.
 *
 * The attributes and cost modifiers are synthetic values authored in
 * [SyntheticFixture]. Only the published map layer is real: it draws the
 * `contours` archive that T-0020 mounts, filtered to index lines, so the seam
 * reaches the rendered map without pretending the attributes came from it.
 * The archive stores `idx` as a Boolean, so the filter compares against `true`;
 * an integer `1` matches no feature because MapLibre compares types.
 */
internal class SyntheticElevationDataSource : DataSource {
    override val contract: ProviderContract = DataSourceContract.REFERENCE

    override fun describe(): DataSourceDescription =
        DataSourceDescription(
            attributeKinds = setOf(SyntheticFixture.ELEVATION_KIND),
            regions = listOf(SyntheticFixture.REGION),
            refreshPolicy = SyntheticFixture.REFRESH_POLICY,
        )

    override suspend fun attributes(
        request: AttributeRequest,
        cancellation: CancellationHandle,
    ): Outcome<AttributePage> {
        if (cancellation.isCancelled()) {
            return Outcome.Failure(AppError.Cancelled)
        }
        if (request.limit !in 1..MAX_PAGE_SIZE) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val available = SyntheticFixture.ways.filter { request.area.contains(it.position) }
        val offset =
            when (val cursor = request.cursor) {
                null -> 0
                else -> decodeCursor(cursor) ?: return Outcome.Failure(AppError.InvalidInput)
            }
        if (offset > available.size) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        if (offset == available.size) {
            return Outcome.Success(AttributePage(items = emptyList(), nextCursor = null))
        }

        val end = minOf(offset + request.limit, available.size)
        val items = mutableListOf<WayAttribute>()
        for (index in offset until end) {
            if (cancellation.isCancelled()) {
                return Outcome.Failure(AppError.Cancelled)
            }
            items += available[index].attribute
        }
        val nextCursor = if (end < available.size) CursorToken("$CURSOR_PREFIX:$end") else null
        return Outcome.Success(AttributePage(items = items, nextCursor = nextCursor))
    }

    override suspend fun costModifiers(
        request: CostModifierRequest,
        cancellation: CancellationHandle,
    ): Outcome<List<CostModifier>> {
        if (cancellation.isCancelled()) {
            return Outcome.Failure(AppError.Cancelled)
        }
        if (request.waypoints.size < MIN_WAYPOINTS) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        if (request.regionBuild != null && request.regionBuild != SyntheticFixture.REGION_BUILD) {
            return Outcome.Success(emptyList())
        }
        if (request.waypoints.none { SyntheticFixture.bounds.contains(it) }) {
            return Outcome.Success(emptyList())
        }
        val modifiers = SyntheticFixture.modifiers
        if (modifiers.any { CostModifierRules.violationOf(it) != null }) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        return Outcome.Success(modifiers)
    }

    override fun mapLayer(): SlottedLayer =
        SlottedLayer(
            slot = LayerSlot.Overlays,
            spec =
                LayerSpec(
                    id = LayerId(MAP_LAYER_ID),
                    type = LayerType.Line,
                    source = MapSourceIds.CONTOURS,
                    sourceLayer = "contours",
                    minZoom = MAP_MIN_ZOOM,
                    maxZoom = MAP_MAX_ZOOM,
                    filter =
                        JsonArray(
                            listOf(
                                JsonPrimitive("=="),
                                JsonPrimitive("idx"),
                                JsonPrimitive(INDEX_CONTOUR),
                            ),
                        ),
                    paint =
                        buildJsonObject {
                            put("line-color", LINE_COLOR)
                            put("line-width", LINE_WIDTH)
                            put("line-opacity", LINE_OPACITY)
                        },
                ),
        )

    private fun decodeCursor(cursor: CursorToken): Int? {
        val parts = cursor.token.split(':')
        if (parts.size != 2 || parts[0] != CURSOR_PREFIX) {
            return null
        }
        return parts[1].toIntOrNull()?.takeIf { it >= 0 }
    }

    internal companion object {
        const val MAX_PAGE_SIZE: Int = 3
        const val CURSOR_PREFIX: String = "elevation"
        const val MAP_LAYER_ID: String = "data-synthetic-contour-index"
        const val INDEX_CONTOUR: Boolean = true
        const val MAP_MIN_ZOOM: Float = 11f
        const val MAP_MAX_ZOOM: Float = 14f
        const val LINE_COLOR: String = "#7a4b24"
        const val LINE_WIDTH: Float = 1.4f
        const val LINE_OPACITY: Float = 0.8f
        private const val MIN_WAYPOINTS = 2
    }
}
