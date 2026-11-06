package nl.r1a.grassmap.map.style

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The T-0020 spike gate: every layer, property and expression in the phase-A
 * bench style must compile to a Compose layer node. Anything the 0.20 API
 * cannot express appears in [StyleCompileReport.unsupported] and fails here.
 */
class BenchStyleCoverageTest {
    @Test
    fun everyBenchLayerCompiles() {
        val report = StyleLayerCompiler.compileAll(benchLayers())

        assertTrue(
            "Unsupported: ${report.unsupported}",
            report.unsupported.isEmpty(),
        )
        assertEquals(BENCH_LAYER_COUNT, report.compiled.size)
    }

    @Test
    fun spikePropertiesArePresentInTheCompiledLayers() {
        val compiled = StyleLayerCompiler.compileAll(benchLayers()).compiled

        assertTrue(compiled.any { it.expression("line-dasharray") != null })
        assertTrue(compiled.any { it.expression("symbol-placement") != null })
        assertTrue(compiled.any { it.expression("text-field") != null })
        assertTrue(compiled.any { it.filter != null })

        val symbolZOrder =
            StyleLayerCompiler.compile(
                LayerSpec(
                    id = LayerId("spike-symbol-z-order"),
                    type = LayerType.Symbol,
                    source = MapSourceIds.REGION,
                    sourceLayer = "places",
                    layout =
                        JsonObject(
                            mapOf(
                                "symbol-placement" to JsonPrimitive("line"),
                                "symbol-z-order" to JsonPrimitive("source"),
                            ),
                        ),
                ),
            )
        assertNotNull(symbolZOrder.expression("symbol-z-order"))
        assertNotNull(symbolZOrder.expression("symbol-placement"))
    }

    @Test
    fun anUnknownPropertyIsReportedNotGuessedAt() {
        val report =
            StyleLayerCompiler.compileAll(
                listOf(
                    SlottedLayer(
                        slot = LayerSlot.Base,
                        spec =
                            LayerSpec(
                                id = LayerId("unknown"),
                                type = LayerType.Line,
                                source = MapSourceIds.REGION,
                                paint = JsonObject(mapOf("line-unknown" to JsonPrimitive(1))),
                            ),
                    ),
                ),
            )

        assertEquals(1, report.unsupported.size)
        assertTrue(
            report.unsupported
                .single()
                .reason
                .contains("line-unknown"),
        )
    }

    private fun benchLayers(): List<SlottedLayer> {
        val text =
            checkNotNull(javaClass.classLoader?.getResourceAsStream("bench-light.json")) {
                "bench-light.json is missing"
            }.bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(text).jsonObject
        val layers =
            (root["layers"] as? JsonArray) ?: error("Bench style has no layers")
        return layers.map { element ->
            val layer = element.jsonObject
            SlottedLayer(
                slot = LayerSlot.Base,
                spec =
                    LayerSpec(
                        id = LayerId(layer["id"].string()),
                        type = layerType(layer["type"].string()),
                        source =
                            layer["source"]
                                ?.let { MapSourceIds.REGION },
                        sourceLayer = layer["source-layer"]?.string(),
                        minZoom = (layer["minzoom"] as? JsonPrimitive)?.floatOrNull ?: 0f,
                        maxZoom = (layer["maxzoom"] as? JsonPrimitive)?.floatOrNull ?: 24f,
                        filter = layer["filter"],
                        paint = layer["paint"] as? JsonObject ?: JsonObject(emptyMap()),
                        layout = layer["layout"] as? JsonObject ?: JsonObject(emptyMap()),
                    ),
            )
        }
    }

    private fun kotlinx.serialization.json.JsonElement?.string(): String =
        checkNotNull((this as? JsonPrimitive)?.contentOrNull) { "Missing string: $this" }

    private fun layerType(value: String): LayerType =
        when (value) {
            "background" -> LayerType.Background
            "fill" -> LayerType.Fill
            "line" -> LayerType.Line
            "symbol" -> LayerType.Symbol
            else -> error("Unsupported test layer type: $value")
        }

    private companion object {
        const val BENCH_LAYER_COUNT = 71
    }
}
