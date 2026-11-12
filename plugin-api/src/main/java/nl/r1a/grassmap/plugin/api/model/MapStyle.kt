package nl.r1a.grassmap.plugin.api.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@JvmInline
value class StyleId(
    val value: String,
) {
    override fun toString(): String = value
}

@JvmInline
value class LayerId(
    val value: String,
) {
    override fun toString(): String = value
}

@JvmInline
value class SourceId(
    val value: String,
) {
    override fun toString(): String = value
}

/**
 * The logical map sources every style resolves against. The host maps each id
 * to a mounted file or a network source; a style names the id, never a path.
 */
object MapSourceIds {
    /** Low zooms, z0-z7, from the world archive. */
    val WORLD: SourceId = SourceId("world")

    /** The mounted region package, z8-z14. */
    val REGION: SourceId = SourceId("region")

    /** Contour lines, z9-z14. */
    val CONTOURS: SourceId = SourceId("contours")
}

enum class SourceKind {
    Vector,
    RasterDem,
}

/**
 * A source a style needs. The host resolves [id] to a loaded source.
 */
data class MapSource(
    val id: SourceId,
    val kind: SourceKind = SourceKind.Vector,
    val minZoom: Int = 0,
    val maxZoom: Int = 24,
    val attribution: String? = null,
)

/**
 * A base style document and the logical sources it needs. The document holds
 * version, glyphs, sprite and an empty layer list; layers arrive through
 * [nl.r1a.grassmap.plugin.api.provider.MapLayerProvider].
 */
data class StyleSpec(
    val id: StyleId,
    val baseStyleJson: String,
    val sources: List<MapSource> = emptyList(),
)

enum class LayerType {
    Background,
    Fill,
    Line,
    Symbol,
    Circle,
    Raster,
    Hillshade,
}

/**
 * Fixed z-order groups. Layers draw in this order, and in declaration order
 * inside a group.
 */
enum class LayerSlot {
    Base,
    Terrain,
    Overlays,
    Routes,
    Markers,
    Labels,
}

/**
 * One MapLibre style layer fragment. Values are style-spec JSON so a provider
 * stays free of map engine types; the host compiles them.
 *
 * A layer draws from [source], the mounted region or contour archive, or from
 * [data], an inline GeoJSON `FeatureCollection`. [data] exists for geometry a
 * plugin assembles itself, such as a route relation's member ways (T-0418);
 * it is not a second base source and is never written to disk. Exactly one of
 * the two is set, and a layer with [data] must not name [sourceLayer].
 */
data class LayerSpec(
    val id: LayerId,
    val type: LayerType,
    val source: SourceId? = null,
    val sourceLayer: String? = null,
    val data: String? = null,
    val minZoom: Float = 0f,
    val maxZoom: Float = 24f,
    val filter: JsonElement? = null,
    val paint: JsonObject = JsonObject(emptyMap()),
    val layout: JsonObject = JsonObject(emptyMap()),
)

data class SlottedLayer(
    val slot: LayerSlot,
    val spec: LayerSpec,
)

/**
 * A replacement for one style's layers. The base style is unchanged, so the
 * host updates layers in place.
 */
data class StyleDiff(
    val styleId: StyleId,
    val layers: List<SlottedLayer>,
)
