package buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleRuleCheckerTest {
    @Test
    fun acceptsAValidDocument() {
        val errors = StyleRuleChecker.check("style.json", validDocument())

        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun rejectsTooFewOrTooManyLayers() {
        val errors =
            StyleRuleChecker.check(
                "style.json",
                validDocument(layers = listOf(fill("land", mapOf("fill-color" to "@land")))),
            )

        assertTrue(errors.any { "expected 75..85" in it })
    }

    @Test
    fun rejectsMoreThanFifteenSymbols() {
        val symbols =
            (0 until 16).map { index ->
                symbol("symbol$index", "places")
            }
        val layers = fills(59) + symbols

        val errors = StyleRuleChecker.check("style.json", document(layers))

        assertTrue(errors.any { "16 symbol layers, at most 15" in it })
    }

    @Test
    fun rejectsAnExpressionFillColour() {
        val layers =
            listOf(
                fill(
                    "landcover",
                    mapOf(
                        "fill-color" to listOf("match", listOf("get", "kind"), "grass", "#00ff00"),
                        "fill-antialias" to false,
                    ),
                    sourceLayer = "landcover",
                ),
            ) + fills(59)

        val errors = StyleRuleChecker.check("style.json", document(layers))

        assertTrue(errors.any { "fill-color must be a constant colour" in it })
    }

    @Test
    fun rejectsTranslucentFill() {
        val layers =
            listOf(
                fill(
                    "landuse",
                    mapOf("fill-color" to "@land", "fill-opacity" to 0.5),
                    sourceLayer = "landuse",
                ),
            ) + fills(59)

        val errors = StyleRuleChecker.check("style.json", document(layers))

        assertTrue(errors.any { "fill-opacity must be 1" in it })
    }

    @Test
    fun rejectsSymbolSortKeyAndDisallowedHalo() {
        val layers =
            listOf(
                symbol("water", "water", layout = mapOf("symbol-sort-key" to listOf("get", "rank"))),
            ) + fills(59)

        val errors = StyleRuleChecker.check("style.json", document(layers))

        assertTrue(errors.any { "symbol-sort-key is not allowed" in it })
        assertTrue(errors.any { "text halos belong to place and street names only" in it })
    }

    @Test
    fun rejectsPatternAndGradientPaint() {
        val layers =
            listOf(
                line("stream", mapOf("line-gradient" to "x")),
                fill("pattern", mapOf("fill-color" to "@land", "fill-pattern" to "x")),
            ) + fills(59)

        val errors = StyleRuleChecker.check("style.json", document(layers))

        assertTrue(errors.any { "'line-gradient' is not allowed" in it })
        assertTrue(errors.any { "'fill-pattern' is not allowed" in it })
    }

    @Test
    fun rejectsMissingZoomAndUnknownToken() {
        val missingZoom = fill("land", mapOf("fill-color" to "@land")).minus("minzoom")
        val layers = listOf(missingZoom, fill("unknown", mapOf("fill-color" to "@missing"))) + fills(59)

        val errors = StyleRuleChecker.check("style.json", document(layers))

        assertTrue(errors.any { "needs explicit minzoom and maxzoom" in it })
        assertTrue(errors.any { "unknown palette token '@missing'" in it })
    }

    @Test
    fun checksEveryVariantWithItsOwnPalette() {
        val document =
            document(fills(58) + line("stream", emptyMap())).toMutableMap().apply {
                put("styles", mapOf("standard" to "light", "standard-dark" to "dark"))
                put(
                    "palettes",
                    mapOf(
                        "light" to mapOf("land" to "#ffffff", "line" to "#111111"),
                        "dark" to mapOf("land" to "#000000"),
                    ),
                )
            }

        val errors = StyleRuleChecker.check("style.json", document)

        assertTrue(errors.any { "standard-dark" in it && "unknown palette token '@line'" in it })
    }

    private fun validDocument(layers: List<Map<String, Any>> = fills(73)): Map<*, *> =
        document(layers + symbol("places", "places"))

    private fun document(layers: List<Map<String, Any>>): Map<*, *> =
        mapOf(
            "styles" to mapOf("standard" to "light", "standard-dark" to "dark"),
            "palettes" to
                mapOf(
                    "light" to mapOf("land" to "#ffffff", "line" to "#111111", "label" to "#222222"),
                    "dark" to mapOf("land" to "#000000", "line" to "#aaaaaa", "label" to "#eeeeee"),
                ),
            "layers" to layers,
        )

    private fun fills(count: Int): List<Map<String, Any>> =
        (0 until count).map { index -> fill("land$index", mapOf("fill-color" to "@land")) }

    private fun fill(
        id: String,
        paint: Map<String, Any?>,
        sourceLayer: String? = null,
    ): Map<String, Any> =
        buildMap {
            put("id", id)
            put("type", "fill")
            put("minzoom", 0)
            put("maxzoom", 24)
            put("paint", paint)
            if (sourceLayer != null) put("source-layer", sourceLayer)
        }

    private fun line(
        id: String,
        paint: Map<String, Any>,
    ): Map<String, Any> =
        mapOf(
            "id" to id,
            "type" to "line",
            "minzoom" to 0,
            "maxzoom" to 24,
            "paint" to (paint + ("line-color" to "@line")),
        )

    private fun symbol(
        id: String,
        sourceLayer: String,
        layout: Map<String, Any> = emptyMap(),
    ): Map<String, Any> =
        mapOf(
            "id" to id,
            "type" to "symbol",
            "minzoom" to 0,
            "maxzoom" to 24,
            "source-layer" to sourceLayer,
            "layout" to (layout + ("symbol-z-order" to "source")),
            "paint" to mapOf("text-color" to "@label", "text-halo-color" to "#ffffff"),
        )
}
