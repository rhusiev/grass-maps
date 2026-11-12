package buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * The design/map-style.md section 4 checks over an authored style document.
 *
 * The document holds one layer list and a palette per variant. Every check runs
 * against each resolved variant, so a dark palette cannot silently change the
 * layer count or the fill mode. Layer counts include the overlay plugins'
 * contour layers, which the contours provider adds to every variant.
 */
internal object StyleRuleChecker {
    private const val TOKEN_PREFIX = "@"
    private const val MIN_LAYERS = 75
    private const val MAX_LAYERS = 85
    private const val MAX_SYMBOL_LAYERS = 15
    private const val OVERLAY_LAYERS = 5
    private val HALO_SOURCES = setOf("places", "roads")
    private val FORBIDDEN_PAINT = listOf("line-pattern", "fill-pattern", "line-gradient")

    fun check(
        name: String,
        document: Map<*, *>,
    ): List<String> {
        val errors = mutableListOf<String>()
        val palettes = document["palettes"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val variants = document["styles"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val layers = document["layers"] as? List<*> ?: emptyList<Any>()
        if (palettes.isEmpty() || variants.isEmpty()) {
            return listOf("$name: needs a non-empty 'palettes' and 'styles' object")
        }
        for ((styleId, paletteName) in variants) {
            val palette = palettes[paletteName] as? Map<*, *>
            if (palette == null) {
                errors += "$name: style '$styleId' names missing palette '$paletteName'"
                continue
            }
            checkVariant(name, styleId.toString(), palette, layers, errors)
        }
        return errors
    }

    private fun checkVariant(
        name: String,
        variant: String,
        palette: Map<*, *>,
        layers: List<*>,
        errors: MutableList<String>,
    ) {
        val where = "$name[$variant]"
        val resolved = layers.map { resolve(it, palette, where, errors) }
        val total = resolved.size + OVERLAY_LAYERS
        if (total !in MIN_LAYERS..MAX_LAYERS) {
            errors +=
                "$where: $total layers including $OVERLAY_LAYERS contour overlays, " +
                "expected $MIN_LAYERS..$MAX_LAYERS"
        }
        val symbols = resolved.count { (it as? Map<*, *>)?.get("type") == "symbol" }
        if (symbols > MAX_SYMBOL_LAYERS) {
            errors += "$where: $symbols symbol layers, at most $MAX_SYMBOL_LAYERS"
        }
        for (layer in resolved) {
            val map = layer as? Map<*, *> ?: continue
            checkLayer(where, map, errors)
        }
    }

    private fun checkLayer(
        where: String,
        layer: Map<*, *>,
        errors: MutableList<String>,
    ) {
        val id = layer["id"]?.toString() ?: "<missing id>"
        val prefix = "$where layer '$id'"
        val type = layer["type"]?.toString()
        if (type == "fill-extrusion") {
            errors += "$prefix: fill-extrusion is not allowed"
        }
        if (layer["minzoom"] !is Number || layer["maxzoom"] !is Number) {
            errors += "$prefix: needs explicit minzoom and maxzoom"
        }
        val paint = layer["paint"] as? Map<*, *> ?: emptyMap<Any, Any>()
        for (forbidden in FORBIDDEN_PAINT) {
            if (paint.containsKey(forbidden)) {
                errors += "$prefix: '$forbidden' is not allowed"
            }
        }
        if (type == "fill") {
            if (paint["fill-color"] !is String) {
                errors += "$prefix: fill-color must be a constant colour"
            }
            val opacity = paint["fill-opacity"]
            if (opacity != null && (opacity as? Number)?.toDouble() != 1.0) {
                errors += "$prefix: fill-opacity must be 1"
            }
        }
        if (type == "symbol") {
            val layout = layer["layout"] as? Map<*, *> ?: emptyMap<Any, Any>()
            if (layout.containsKey("symbol-sort-key")) {
                errors += "$prefix: symbol-sort-key is not allowed"
            }
            if (layout["symbol-z-order"] != "source") {
                errors += "$prefix: symbol-z-order must be source"
            }
            val halo =
                paint.containsKey("text-halo-color") || paint.containsKey("text-halo-width")
            if (halo && layer["source-layer"]?.toString() !in HALO_SOURCES) {
                errors += "$prefix: text halos belong to place and street names only"
            }
        }
    }

    private fun resolve(
        value: Any?,
        palette: Map<*, *>,
        where: String,
        errors: MutableList<String>,
    ): Any? =
        when (value) {
            is Map<*, *> -> value.mapValues { resolve(it.value, palette, where, errors) }
            is List<*> -> value.map { resolve(it, palette, where, errors) }
            is String ->
                if (value.startsWith(TOKEN_PREFIX)) {
                    palette[value.removePrefix(TOKEN_PREFIX)]
                        ?: run {
                            errors += "$where: unknown palette token '$value'"
                            value
                        }
                } else {
                    value
                }
            else -> value
        }
}

/** Runs [StyleRuleChecker] over every registered style document. */
abstract class LintStyleRulesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val styles: ConfigurableFileCollection

    @TaskAction
    fun lint() {
        val errors = mutableListOf<String>()
        for (file in styles.files.sortedBy { it.path }) {
            if (!file.isFile) {
                errors += "Missing style document: ${file.path}"
                continue
            }
            val document =
                JsonSlurper().parse(file) as? Map<*, *>
                    ?: run {
                        errors += "${file.path}: the style document must be a JSON object"
                        continue
                    }
            errors += StyleRuleChecker.check(file.name, document)
        }
        if (errors.isNotEmpty()) {
            throw GradleException(
                "Style rule violations (design/map-style.md section 4):\n" +
                    errors.joinToString("\n") { "  $it" },
            )
        }
    }
}
