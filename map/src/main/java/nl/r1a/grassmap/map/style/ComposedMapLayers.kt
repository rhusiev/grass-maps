package nl.r1a.grassmap.map.style

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import nl.r1a.grassmap.map.MapHostState
import nl.r1a.grassmap.map.PuckAgeFormatter
import nl.r1a.grassmap.map.PuckDisplay
import nl.r1a.grassmap.map.PuckGeometry
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.SourceId
import nl.r1a.grassmap.plugin.api.provider.Route
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpPaddingValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.FormattedValue
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.ListValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.expressions.value.SymbolPlacement
import org.maplibre.compose.expressions.value.SymbolZOrder
import org.maplibre.compose.expressions.value.TextJustify
import org.maplibre.compose.expressions.value.TextRotationAlignment
import org.maplibre.compose.expressions.value.TextTransform
import org.maplibre.compose.expressions.value.TextUnitOffsetValue
import org.maplibre.compose.expressions.value.TextUnitValue
import org.maplibre.compose.expressions.value.VectorValue
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LayerDefaults
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.sources.getBaseSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.MaplibreComposable

private const val TAG = "GrassMapsMap"

/**
 * Declares every composed style layer to the map. Layers draw slot by slot and
 * in declaration order inside each slot.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
@MaplibreComposable
internal fun ComposedMapLayers(session: MapHostState) {
    val styleReady = session.styleReady.value
    val owned = session.layers.value
    val compiled =
        remember(owned) {
            owned.mapNotNull { layer ->
                try {
                    StyleLayerCompiler.compile(layer.spec) to layer.slot
                } catch (exception: StyleCompileException) {
                    Log.w(TAG, "Skipping ${layer.spec.id}: ${exception.message}")
                    null
                }
            }
        }
    val sources = rememberBaseSources()
    for (slot in LayerSlot.entries) {
        for ((layer, layerSlot) in compiled) {
            if (layerSlot != slot) continue
            val source = layer.source?.let { sources[it] }
            StyleLayerNode(layer, source)
        }
        when (slot) {
            LayerSlot.Routes -> RouteLayerSlot(session)
            LayerSlot.Markers -> MarkerLayerSlot(session, styleReady)
            else -> Unit
        }
    }
}

/**
 * The route slot. Kept out of [ComposedMapLayers] so a new route re-runs only
 * this small composable, not every style layer.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
@MaplibreComposable
private fun RouteLayerSlot(session: MapHostState) {
    RouteLine(session.route.value)
}

/**
 * The marker slot. The camera and puck are per-frame state: reading them here
 * keeps every pan or puck frame from re-running the whole style layer tree.
 * The accuracy radius is bucketed by zoom and latitude, so a pan at a fixed
 * zoom keeps the puck layers out of recomposition.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
@MaplibreComposable
private fun MarkerLayerSlot(
    session: MapHostState,
    styleReady: Boolean,
) {
    val puck = session.puck.value
    val camera by session.camera.collectAsState()
    val density = LocalDensity.current.density
    val zoomBucket = (camera.zoom * ZOOM_BUCKET_STEPS).toInt() / ZOOM_BUCKET_STEPS.toDouble()
    val latitudeBucket = ((puck?.fix?.position?.latitude ?: 0.0) * LATITUDE_BUCKET_STEPS).toInt()
    val accuracyRadius =
        remember(zoomBucket, latitudeBucket, puck?.fix?.accuracyMeters, puck?.showAccuracy, density) {
            if (puck?.showAccuracy == true) {
                PuckGeometry
                    .accuracyRadiusDp(
                        accuracyMeters = puck.fix.accuracyMeters,
                        latitude = puck.fix.position.latitude,
                        zoom = zoomBucket,
                        density = density,
                    ).dp
            } else {
                0.dp
            }
        }
    key(session.styleGeneration.value, styleReady) {
        LocationPuckLayer(puck, accuracyRadius, session.ageLabel)
    }
}

/**
 * The planned route as one GeoJSON line in the routes slot. The source is
 * re-keyed when the route changes, matching the puck's update pattern.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
@MaplibreComposable
private fun RouteLine(route: Route?) {
    if (route == null || route.geometry.size < MIN_ROUTE_POINTS) return
    val source = rememberGeoJsonSource(GeoJsonData.JsonString(route.toFeatureCollection()))
    LineLayer(
        id = ROUTE_LAYER_ID,
        source = source,
        color = const(ROUTE_COLOR),
        opacity = const(1f),
        width = const(ROUTE_WIDTH_DP.dp),
    )
}

/**
 * The own-position puck. It is a GeoJSON point source with three layers: an
 * accuracy circle when the fix is poor, a filled dot for a fresh fix, and a
 * grey ring with age text for a stale one. The source is re-keyed each frame
 * while the puck animates, which is the pattern maplibre-compose 0.20 supports
 * for a moving feature.
 *
 * A map gesture does not stop following yet; T-0106 owns the camera-mode button.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
@MaplibreComposable
private fun LocationPuckLayer(
    display: PuckDisplay?,
    accuracyRadius: Dp,
    ageLabel: PuckAgeFormatter,
) {
    if (display == null) return
    val source = rememberGeoJsonSource(GeoJsonData.JsonString(display.fix.toFeatureCollection()))
    CircleLayer(
        id = ACCURACY_LAYER_ID,
        source = source,
        radius = const(accuracyRadius),
        color = const(if (display.showAccuracy) ACCURACY_COLOR else Color.Transparent),
        strokeColor = const(if (display.showAccuracy) ACCURACY_STROKE_COLOR else Color.Transparent),
        strokeWidth = const(if (display.showAccuracy) ACCURACY_STROKE_WIDTH_DP.dp else 0.dp),
    )
    CircleLayer(
        id = PUCK_LAYER_ID,
        source = source,
        radius = const(if (display.isStale) PUCK_STALE_RADIUS_DP.dp else PUCK_RADIUS_DP.dp),
        color = const(if (display.isStale) Color.Transparent else PUCK_COLOR),
        strokeColor = const(if (display.isStale) PUCK_STALE_COLOR else PUCK_STROKE_COLOR),
        strokeWidth = const(PUCK_STROKE_WIDTH_DP.dp),
    )
    SymbolLayer(
        id = PUCK_AGE_LAYER_ID,
        source = source,
        textField = const(if (display.isStale) ageLabel.format(display.ageMillis) else "").cast(),
        textFont = const(listOf(PUCK_FONT)),
        textSize = const(PUCK_AGE_TEXT_SIZE.sp),
        textAnchor = const(SymbolAnchor.Bottom),
        textColor = const(PUCK_AGE_TEXT_COLOR),
        textHaloColor = const(PUCK_AGE_HALO_COLOR),
        textHaloWidth = const(PUCK_AGE_HALO_WIDTH_DP.dp),
    )
}

private fun LocationFix.toFeatureCollection(): String =
    buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            addJsonObject {
                put("type", "Feature")
                putJsonObject("properties") {}
                putJsonObject("geometry") {
                    put("type", "Point")
                    putJsonArray("coordinates") {
                        add(position.longitude)
                        add(position.latitude)
                    }
                }
            }
        }
    }.toString()

private const val PUCK_LAYER_ID = "core.location.puck"
private const val PUCK_AGE_LAYER_ID = "core.location.puck.age"
private const val ACCURACY_LAYER_ID = "core.location.accuracy"

// The accuracy circle radius is derived from zoom and latitude. Bucketing both
// keeps a pan or a small move from recreating the puck layers every frame.
private const val ZOOM_BUCKET_STEPS = 10
private const val LATITUDE_BUCKET_STEPS = 100.0
private const val PUCK_FONT = "Noto Sans Regular"
private const val PUCK_RADIUS_DP = 7
private const val PUCK_STALE_RADIUS_DP = 9
private const val PUCK_STROKE_WIDTH_DP = 2
private const val PUCK_AGE_TEXT_SIZE = 11
private const val PUCK_AGE_HALO_WIDTH_DP = 2
private const val ACCURACY_STROKE_WIDTH_DP = 1
private val PUCK_COLOR = Color(0xFF1E88E5)
private val PUCK_STROKE_COLOR = Color.White
private val PUCK_STALE_COLOR = Color(0xFF9E9E9E)
private val PUCK_AGE_TEXT_COLOR = Color(0xFF424242)
private val PUCK_AGE_HALO_COLOR = Color.White
private val ACCURACY_COLOR = Color(0x221E88E5)
private val ACCURACY_STROKE_COLOR = Color(0x551E88E5)

private const val ROUTE_LAYER_ID = "core.route.line"
private const val ROUTE_WIDTH_DP = 5
private const val MIN_ROUTE_POINTS = 2
private val ROUTE_COLOR = Color(0xFF2E7D32)

@Composable
@MaplibreComposable
private fun rememberBaseSources(): Map<SourceId, VectorSource> {
    val world = getBaseSource<VectorSource>(MapSourceIds.WORLD.value)
    val region = getBaseSource<VectorSource>(MapSourceIds.REGION.value)
    val contours = getBaseSource<VectorSource>(MapSourceIds.CONTOURS.value)
    return buildMap {
        world?.let { put(MapSourceIds.WORLD, it) }
        region?.let { put(MapSourceIds.REGION, it) }
        contours?.let { put(MapSourceIds.CONTOURS, it) }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
@MaplibreComposable
private fun StyleLayerNode(
    layer: CompiledLayer,
    source: VectorSource?,
) {
    when (layer.type) {
        LayerType.Background -> {
            BackgroundLayer(
                id = layer.id,
                minZoom = layer.minZoom,
                maxZoom = layer.maxZoom,
                color = layer.expression<ColorValue>("background-color") ?: const(Color.Black),
            )
        }

        LayerType.Fill -> {
            if (source == null) return
            FillLayer(
                id = layer.id,
                source = source,
                sourceLayer = layer.sourceLayer,
                minZoom = layer.minZoom,
                maxZoom = layer.maxZoom,
                filter = layer.filter,
                color = layer.expression<ColorValue>("fill-color") ?: const(Color.Black),
                opacity = layer.expression<FloatValue>("fill-opacity") ?: const(1f),
            )
        }

        LayerType.Line -> {
            if (source == null) return
            LineLayer(
                id = layer.id,
                source = source,
                sourceLayer = layer.sourceLayer,
                minZoom = layer.minZoom,
                maxZoom = layer.maxZoom,
                filter = layer.filter,
                color = layer.expression<ColorValue>("line-color") ?: const(Color.Black),
                opacity = layer.expression<FloatValue>("line-opacity") ?: const(1f),
                dasharray = layer.expression<VectorValue<Number>>("line-dasharray"),
                width = layer.expression<DpValue>("line-width") ?: const(1.dp),
                gapWidth = layer.expression<DpValue>("line-gap-width") ?: const(0.dp),
            )
        }

        LayerType.Symbol -> {
            if (source == null) return
            SymbolLayer(
                id = layer.id,
                source = source,
                sourceLayer = layer.sourceLayer,
                minZoom = layer.minZoom,
                maxZoom = layer.maxZoom,
                filter = layer.filter,
                sortKey = layer.expression<FloatValue>("symbol-sort-key"),
                placement =
                    layer.expression<SymbolPlacement>("symbol-placement")
                        ?: const(SymbolPlacement.Point),
                spacing = layer.expression<DpValue>("symbol-spacing") ?: const(250.dp),
                zOrder =
                    layer.expression<SymbolZOrder>("symbol-z-order")
                        ?: const(SymbolZOrder.Auto),
                iconImage = layer.expression<ImageValue?>("icon-image"),
                iconSize = layer.expression<FloatValue>("icon-size") ?: const(1f),
                iconRotate = layer.expression<FloatValue>("icon-rotate") ?: const(0f),
                iconRotationAlignment =
                    layer.expression<IconRotationAlignment>("icon-rotation-alignment")
                        ?: const(IconRotationAlignment.Auto),
                iconPadding =
                    layer.expression<DpPaddingValue>("icon-padding")
                        ?: const(DpPadding(2.dp, 2.dp, 2.dp, 2.dp)),
                textField = layer.expression<FormattedValue?>("text-field") ?: const("").cast(),
                textFont =
                    layer.expression<ListValue<StringValue>>("text-font") ?: LayerDefaults.FontNames,
                textSize = layer.expression<TextUnitValue>("text-size") ?: const(1.em),
                textTransform =
                    layer.expression<TextTransform>("text-transform") ?: const(TextTransform.None),
                textLetterSpacing =
                    layer.expression<TextUnitValue>("text-letter-spacing") ?: const(0.em),
                textMaxWidth = layer.expression<TextUnitValue>("text-max-width") ?: const(10.em),
                textJustify =
                    layer.expression<TextJustify>("text-justify") ?: const(TextJustify.Center),
                textAnchor =
                    layer.expression<SymbolAnchor>("text-anchor") ?: const(SymbolAnchor.Center),
                textOffset =
                    layer.expression<TextUnitOffsetValue>("text-offset")
                        ?: textOffset(0.em, 0.em),
                textRadialOffset =
                    layer.expression<TextUnitValue>("text-radial-offset") ?: const(0.em),
                textRotationAlignment =
                    layer.expression<TextRotationAlignment>("text-rotation-alignment")
                        ?: const(TextRotationAlignment.Auto),
                textVariableAnchor = layer.expression<ListValue<SymbolAnchor>>("text-variable-anchor"),
                textPadding = layer.expression<DpValue>("text-padding") ?: const(2.dp),
                textColor = layer.expression<ColorValue>("text-color") ?: const(Color.Black),
                textHaloColor =
                    layer.expression<ColorValue>("text-halo-color") ?: const(Color.Transparent),
                textHaloWidth = layer.expression<DpValue>("text-halo-width") ?: const(0.dp),
            )
        }

        else -> {
            Unit
        }
    }
}

private inline fun <reified T : ExpressionValue?> CompiledLayer.expression(name: String): Expression<T>? = properties[name]?.cast()
