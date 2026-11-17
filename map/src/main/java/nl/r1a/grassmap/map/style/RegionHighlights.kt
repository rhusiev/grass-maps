package nl.r1a.grassmap.map.style

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RegionOutline
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenColors

/** The one layer pair the map draws for the installed regions. */
internal object RegionHighlightStyle {
    const val FILL_LAYER_ID: String = "region.highlight.fill"
    const val LINE_LAYER_ID: String = "region.highlight.line"
    val fillColour: Color = Color(NavigationScreenColors.GREEN)
    const val FILL_OPACITY: Float = 0.18f
    val lineColour: Color = Color(NavigationScreenColors.GREEN)
    val lineWidth: Dp = 2.dp
}

/**
 * The highlighted regions as one GeoJSON `FeatureCollection`, one `MultiPolygon`
 * per region. A region with no ring has nothing to draw and is left out.
 */
internal object RegionHighlightGeoJson {
    fun featureCollection(regions: List<RegionOutline>): String =
        buildJsonObject {
            put("type", "FeatureCollection")
            put("features", JsonArray(regions.filter { it.polygons.isNotEmpty() }.map(::feature)))
        }.toString()

    private fun feature(region: RegionOutline): JsonObject =
        buildJsonObject {
            put("type", "Feature")
            put("properties", buildJsonObject { put("region", region.region) })
            put(
                "geometry",
                buildJsonObject {
                    put("type", "MultiPolygon")
                    put("coordinates", JsonArray(region.polygons.map { ring -> JsonArray(listOf(closedRing(ring))) }))
                },
            )
        }

    /** GeoJSON rings repeat their first position at the end. */
    private fun closedRing(ring: List<GeoPoint>): JsonArray {
        val closed = if (ring.firstOrNull() == ring.lastOrNull()) ring else ring + ring.first()
        return JsonArray(
            closed.map { point -> JsonArray(listOf(JsonPrimitive(point.longitude), JsonPrimitive(point.latitude))) },
        )
    }
}
