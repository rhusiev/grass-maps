package nl.r1a.grassmap.map.style

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.SourceId
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.BooleanValue

/**
 * Turns a style layer fragment into the expression AST this host installs.
 * An unknown property or expression is reported, never guessed at.
 */
internal object StyleLayerCompiler {
    private val BACKGROUND_PROPERTIES = setOf("background-color")

    private val FILL_PROPERTIES = setOf("fill-color", "fill-opacity")

    private val LINE_PROPERTIES =
        setOf(
            "line-color",
            "line-width",
            "line-gap-width",
            "line-offset",
            "line-dasharray",
            "line-opacity",
            "line-cap",
            "line-join",
        )

    private val CIRCLE_PROPERTIES =
        setOf(
            "circle-color",
            "circle-radius",
            "circle-stroke-color",
            "circle-stroke-width",
        )

    private val SYMBOL_PROPERTIES =
        setOf(
            "icon-allow-overlap",
            "icon-color",
            "icon-halo-color",
            "icon-halo-width",
            "icon-ignore-placement",
            "icon-image",
            "icon-offset",
            "icon-opacity",
            "icon-padding",
            "icon-rotate",
            "icon-rotation-alignment",
            "icon-size",
            "symbol-placement",
            "symbol-sort-key",
            "symbol-spacing",
            "symbol-z-order",
            "text-anchor",
            "text-field",
            "text-font",
            "text-justify",
            "text-letter-spacing",
            "text-max-width",
            "text-offset",
            "text-padding",
            "text-radial-offset",
            "text-rotation-alignment",
            "text-size",
            "text-transform",
            "text-variable-anchor",
            "text-color",
            "text-halo-color",
            "text-halo-width",
        )

    fun propertiesFor(type: LayerType): Set<String> =
        when (type) {
            LayerType.Background -> BACKGROUND_PROPERTIES
            LayerType.Fill -> FILL_PROPERTIES
            LayerType.Line -> LINE_PROPERTIES
            LayerType.Circle -> CIRCLE_PROPERTIES
            LayerType.Symbol -> SYMBOL_PROPERTIES
            else -> emptySet()
        }

    fun compile(spec: LayerSpec): CompiledLayer {
        when (spec.type) {
            LayerType.Raster,
            LayerType.Hillshade,
            -> throw StyleCompileException("Layer type '${spec.type}' is not rendered yet")

            else -> Unit
        }
        val supported = propertiesFor(spec.type)
        val properties = LinkedHashMap<String, Expression<*>>()
        for (section in listOf(spec.paint, spec.layout)) {
            for ((name, value) in section) {
                if (name !in supported) {
                    throw StyleCompileException("Unsupported property '$name' on ${spec.id}")
                }
                properties[name] = StyleExpression.value(value)
            }
        }
        val filter = spec.filter?.let { StyleExpression.filter(it) }
        val data = spec.data

        if (spec.type == LayerType.Background && (spec.source != null || data != null)) {
            throw StyleCompileException("Background layer ${spec.id} cannot have a source")
        }
        if (data != null && spec.source != null) {
            throw StyleCompileException("Layer ${spec.id} names both a source and inline data")
        }
        if (data != null && spec.sourceLayer != null) {
            throw StyleCompileException("Layer ${spec.id} names a source layer for inline data")
        }
        if (data != null && data.isBlank()) {
            throw StyleCompileException("Layer ${spec.id} has empty inline data")
        }
        return CompiledLayer(
            id = spec.id.value,
            type = spec.type,
            source = spec.source,
            sourceLayer = spec.sourceLayer.orEmpty(),
            data = data,
            minZoom = spec.minZoom,
            maxZoom = spec.maxZoom,
            filter = filter,
            properties = properties,
        )
    }

    fun compileAll(layers: List<SlottedLayer>): StyleCompileReport {
        val compiled = mutableListOf<CompiledLayer>()
        val unsupported = mutableListOf<UnsupportedProperty>()
        for (slotted in layers) {
            try {
                compiled += compile(slotted.spec)
            } catch (exception: StyleCompileException) {
                unsupported +=
                    UnsupportedProperty(
                        layerId = slotted.spec.id.value,
                        reason = exception.message.orEmpty(),
                    )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // Malformed plugin JSON reaches array access or casts that throw
                // beyond the compiler's own checks. A layer may not cross the seam
                // with an exception, so report it and keep checking the rest.
                unsupported +=
                    UnsupportedProperty(
                        layerId = slotted.spec.id.value,
                        reason = exception.message ?: exception::class.simpleName.orEmpty(),
                    )
            }
        }
        return StyleCompileReport(compiled = compiled, unsupported = unsupported)
    }
}

internal data class CompiledLayer(
    val id: String,
    val type: LayerType,
    val source: SourceId?,
    val sourceLayer: String,
    val data: String?,
    val minZoom: Float,
    val maxZoom: Float,
    val filter: Expression<BooleanValue>?,
    val properties: Map<String, Expression<*>>,
) {
    fun expression(name: String): Expression<*>? = properties[name]
}

internal data class UnsupportedProperty(
    val layerId: String,
    val reason: String,
)

internal data class StyleCompileReport(
    val compiled: List<CompiledLayer>,
    val unsupported: List<UnsupportedProperty>,
) {
    val isComplete: Boolean
        get() = unsupported.isEmpty()
}
