package nl.r1a.grassmaps.map.style

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmaps.map.LabelLanguage
import nl.r1a.grassmaps.map.LabelOptions
import nl.r1a.grassmaps.plugin.api.model.LayerId
import nl.r1a.grassmaps.plugin.api.model.LayerSpec
import nl.r1a.grassmaps.plugin.api.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LabelStyleTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private val authoredName =
        json(
            """["case",["!=",["get","script"],"Cyrillic"],
            ["format",["coalesce",["get","name:uk"],["get","name:en"]],{},"\n",{},["get","name"],{}],
            ["format",["coalesce",["get","name:uk"],["get","name"]],{}]]""",
        )

    private fun label(
        field: JsonElement = authoredName,
        size: JsonElement = JsonPrimitive(12),
        type: LayerType = LayerType.Symbol,
    ) = LayerSpec(
        id = LayerId("places"),
        type = type,
        layout = JsonObject(mapOf("text-field" to field, "text-size" to size)),
    )

    private fun apply(
        spec: LayerSpec,
        options: LabelOptions,
    ) = LabelStyle.apply(spec, options)

    @Test
    fun theDefaultOptionsReturnTheSameLayer() {
        val spec = label()

        assertSame(spec, apply(spec, LabelOptions()))
    }

    @Test
    fun englishReadsTheEnglishNameThenTheLocalName() {
        val result = apply(label(), LabelOptions(language = LabelLanguage.English))

        assertEquals(
            json("""["coalesce",["get","name:en"],["get","pgf:name"],["get","name"]]"""),
            result.layout["text-field"],
        )
    }

    @Test
    fun ukrainianReadsTheUkrainianNameThenTheLocalName() {
        val result = apply(label(), LabelOptions(language = LabelLanguage.Ukrainian))

        assertEquals(
            json("""["coalesce",["get","name:uk"],["get","pgf:name"],["get","name"]]"""),
            result.layout["text-field"],
        )
    }

    @Test
    fun aFieldWithoutANameRuleKeepsItsText() {
        val field = json("""["get","addr_housenumber"]""")

        val result = apply(label(field = field), LabelOptions(language = LabelLanguage.English))

        assertEquals(field, result.layout["text-field"])
    }

    @Test
    fun theRuleAroundTheNameStays() {
        val field = json("""["step",["zoom"],["get","ref"],6,["case",["has","name:uk"],["get","name:uk"],["get","name"]]]""")

        val result = apply(label(field = field), LabelOptions(language = LabelLanguage.English))

        assertEquals(
            json("""["step",["zoom"],["get","ref"],6,["coalesce",["get","name:en"],["get","pgf:name"],["get","name"]]]"""),
            result.layout["text-field"],
        )
    }

    @Test
    fun aNumberSizeTakesTheFactor() {
        val result = apply(label(), LabelOptions(sizePercent = 150))

        assertEquals(JsonPrimitive(18.0), result.layout["text-size"])
    }

    @Test
    fun aZoomCurveScalesItsOutputsAndKeepsZoomAtTheTop() {
        val size = json("""["interpolate",["linear"],["zoom"],3,10,10,12]""")

        val result = apply(label(size = size), LabelOptions(sizePercent = 150))

        assertEquals(json("""["interpolate",["linear"],["zoom"],3,15.0,10,18.0]"""), result.layout["text-size"])
    }

    @Test
    fun aStepScalesEveryOutput() {
        val size = json("""["step",["zoom"],10,8,14]""")

        val result = apply(label(size = size), LabelOptions(sizePercent = 200))

        assertEquals(json("""["step",["zoom"],20.0,8,28.0]"""), result.layout["text-size"])
    }

    @Test
    fun aDataDrivenCurveOutputIsMultiplied() {
        val size = json("""["interpolate",["linear"],["zoom"],2,["case",["<",["get","rank"],13],8,13],4,10]""")

        val result = apply(label(size = size), LabelOptions(sizePercent = 150))

        assertEquals(
            json("""["interpolate",["linear"],["zoom"],2,["case",["<",["get","rank"],13],12.0,19.5],4,15.0]"""),
            result.layout["text-size"],
        )
    }

    @Test
    fun anExpressionWithoutAZoomCurveIsWrappedInAProduct() {
        val size = json("""["get","size"]""")

        val result = apply(label(size = size), LabelOptions(sizePercent = 50))

        assertEquals(json("""["*",0.5,["get","size"]]"""), result.layout["text-size"])
    }

    @Test
    fun aLayerThatIsNotASymbolIsNotChanged() {
        val spec = label(type = LayerType.Line)

        assertSame(spec, apply(spec, LabelOptions(LabelLanguage.English, 150)))
    }

    @Test
    fun languageAndSizeApplyTogether() {
        val result = apply(label(), LabelOptions(LabelLanguage.English, 150))

        assertEquals(JsonPrimitive(18.0), result.layout["text-size"])
        assertEquals(
            json("""["coalesce",["get","name:en"],["get","pgf:name"],["get","name"]]"""),
            result.layout["text-field"],
        )
    }

    @Test
    fun theChangedLayerStillCompiles() {
        val size = json("""["interpolate",["linear"],["zoom"],3,10,10,12]""")
        val changed = apply(label(size = size), LabelOptions(LabelLanguage.English, 150))

        val compiled = StyleLayerCompiler.compile(changed)

        assertEquals(setOf("text-field", "text-size"), compiled.properties.keys)
    }

    @Test
    fun theSizeFactorIsThePercentOverOneHundred() {
        assertEquals(1.5, LabelOptions(sizePercent = 150).sizeFactor, 0.0)
        assertEquals(1.0, LabelOptions().sizeFactor, 0.0)
    }
}
