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

/**
 * The credits the vector sources carry, as the HTML the map's attribution
 * overlay parses. The ODbL asks for the OpenStreetMap credit to be visible
 * with the map and to link to the copyright page. The online OSM raster
 * preset uses the same string, so the overlay shows the credit once. The contours derive from
 * Copernicus DEM GLO-30, whose terms ask for the credit below
 * (`ATTRIBUTION.md`). Natural Earth is public domain and needs none.
 */
object MapCredits {
    const val OPENSTREETMAP: String =
        "© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"
    const val COPERNICUS_DEM: String =
        "© DLR e.V. and © Airbus Defence and Space GmbH, provided under Copernicus by the EU and ESA"
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
 *
 * [Underlay] is the one group below [Base], so a raster layer there draws under
 * every base-style layer, the opaque background included (T-0514). A raster
 * overlay sits in [Terrain]: above every base-style layer - fills, lines and
 * symbols - and below the contour, track, route, marker and label groups, so a
 * base style's own labels show through it rather than above it.
 */
enum class LayerSlot {
    Underlay,
    Base,
    Terrain,
    Overlays,
    Routes,
    Markers,
    Labels,
}

/**
 * The raster tile source a [LayerSpec] carries itself. Raster tiles are not a
 * mounted archive, so the layer owns the source instead of naming a logical
 * `world`/`region`/`contours` id (T-0512).
 *
 * [tiles] are MapLibre tile URL templates such as
 * `https://tile.openstreetmap.org/{z}/{x}/{y}.png`. [attributionHtml] is the
 * credit the map's attribution overlay shows; it is required for a source
 * whose licence asks for one. The engine fetches these URLs only through the
 * host's network gate (map module), not around it.
 *
 * The source carries no transparency: a layer sets the `raster-opacity` paint
 * property instead, which is the style property the renderer applies (T-0514).
 */
data class RasterSourceSpec(
    val tiles: List<String>,
    val tileSize: Int = 256,
    val minZoom: Int = 0,
    val maxZoom: Int = 24,
    val attributionHtml: String? = null,
)

/**
 * One MapLibre style layer fragment. Values are style-spec JSON so a provider
 * stays free of map engine types; the host compiles them.
 *
 * A layer draws from [source], the mounted region or contour archive, from
 * [data], an inline GeoJSON `FeatureCollection`, or from [raster], a tile URL
 * template the layer brings itself. [data] exists for geometry a plugin
 * assembles itself, such as a route relation's member ways (T-0418); it is not
 * a second base source and is never written to disk. Exactly one of the three
 * is set. A layer with [data] must not name [sourceLayer]; only a raster layer
 * sets [raster].
 */
data class LayerSpec(
    val id: LayerId,
    val type: LayerType,
    val source: SourceId? = null,
    val sourceLayer: String? = null,
    val data: String? = null,
    val raster: RasterSourceSpec? = null,
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
