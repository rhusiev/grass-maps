package nl.r1a.grassmap.map.style

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import nl.r1a.grassmap.map.MapHostState
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.SourceId
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
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LayerDefaults
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.sources.getBaseSource
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
    }
}

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
