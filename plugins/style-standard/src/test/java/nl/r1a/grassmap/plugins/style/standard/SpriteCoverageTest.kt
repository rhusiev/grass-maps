package nl.r1a.grassmap.plugins.style.standard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * The sprite is the other half of the style: an `icon-image` name that has no
 * entry draws nothing and reports nothing. These tests derive the names the
 * style can emit from its own expressions, then hold the committed sheet to
 * them, and check every entry is a single-colour SDF glyph.
 */
class SpriteCoverageTest {
    private val styleFile = File("src/main/styles/standard.json")
    private val spriteFile = File("../../app/src/main/assets/sprites/standard.json")
    private val spriteImage = File("../../app/src/main/assets/sprites/standard.png")
    private val spriteImage2x = File("../../app/src/main/assets/sprites/standard@2x.png")

    @Test
    fun everyIconNameTheStyleCanEmitIsInTheSheet() {
        val expected = iconNames(Json.parseToJsonElement(styleFile.readText()).jsonObject)
        val entries = spriteEntries()

        val missing = expected - entries.keys
        assertTrue("icons missing from the sprite: ${missing.sorted()}", missing.isEmpty())
    }

    @Test
    fun everySpriteEntryIsAnSdfGlyph() {
        for ((name, entry) in spriteEntries()) {
            assertEquals("$name must be an SDF image", true, entry.boolean("sdf"))
        }
    }

    @Test
    fun bothSpriteSheetsHoldWhiteGlyphs() {
        for (file in listOf(spriteImage, spriteImage2x)) {
            val image = requireNotNull(ImageIO.read(file)) { "${file.name} is missing" }
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val pixel = image.getRGB(x, y)
                    assertEquals(
                        "${file.name} pixel ($x, $y) is not a white glyph",
                        0xFFFFFF,
                        pixel and 0xFFFFFF,
                    )
                }
            }
        }
    }

    private fun spriteEntries(): Map<String, JsonObject> =
        Json
            .parseToJsonElement(spriteFile.readText())
            .jsonObject
            .mapValues { it.value.jsonObject }

    private fun iconNames(style: JsonObject): Set<String> {
        val names = mutableSetOf<String>()
        for (layer in style.getValue("layers").jsonArray) {
            val objectLayer = layer.jsonObject
            if (objectLayer.string("type") != "symbol") continue
            val iconImage = objectLayer["layout"]?.jsonObject?.get("icon-image") ?: continue
            names += emit(iconImage, context(objectLayer)).filter { it.isNotEmpty() }
        }
        return names
    }

    private fun context(layer: JsonObject): Map<String, Set<String>> {
        val filter = layer["filter"] ?: JsonArray(emptyList())
        val kinds = poiKinds(filter)
        val shieldLengths = shieldLengths(filter)
        return buildMap {
            if (kinds.isNotEmpty()) put("kind", kinds)
            if (shieldLengths.isNotEmpty()) put("length:shield_text", shieldLengths)
        }
    }

    /** Every `kind` value the layer's filter admits. */
    private fun poiKinds(filter: JsonElement): Set<String> {
        val array = filter as? JsonArray ?: return emptySet()
        val head = array.firstOrNull()?.stringValue()
        val input = array.getOrNull(1) as? JsonArray
        if (head == "in" &&
            input?.firstOrNull()?.stringValue() == "get" &&
            input.getOrNull(1)?.stringValue() == "kind"
        ) {
            val literal = array.getOrNull(2) as? JsonArray
            return literal
                ?.getOrNull(1)
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.toSet()
                .orEmpty()
        }
        return array.flatMap { poiKinds(it) }.toSet()
    }

    /** `1..n` from the filter's `["<=", ["length", ["get", "shield_text"]], n]`. */
    private fun shieldLengths(filter: JsonElement): Set<String> {
        val array = filter as? JsonArray ?: return emptySet()
        val input = array.getOrNull(1) as? JsonArray
        val limit = (array.getOrNull(2) as? JsonPrimitive)?.intOrNull
        val isLength =
            array.firstOrNull()?.stringValue() == "<=" &&
                input?.firstOrNull()?.stringValue() == "length"
        if (isLength && limit != null) {
            return (1..limit).map { it.toString() }.toSet()
        }
        return array.flatMap { shieldLengths(it) }.toSet()
    }

    private fun emit(
        expression: JsonElement,
        context: Map<String, Set<String>>,
        restrictTo: Set<String>? = null,
    ): Set<String> {
        if (expression is JsonPrimitive) {
            val value = expression.contentOrNull.orEmpty()
            return if (restrictTo == null || value in restrictTo) setOf(value) else emptySet()
        }
        val parts = expression.jsonArray
        return when (parts.firstOrNull()?.stringValue()) {
            "get" -> {
                val field = parts.getOrNull(1)?.stringValue() ?: return emptySet()
                val values = context[field] ?: return emptySet()
                if (restrictTo == null) values else values intersect restrictTo
            }

            "literal" -> {
                emit(parts.getOrNull(1) ?: return emptySet(), context, restrictTo)
            }

            "concat" -> {
                concat(parts.drop(1), context)
            }

            "match" -> {
                match(parts, context)
            }

            "case" -> {
                caseOutputs(parts.drop(1), context)
            }

            "step" -> {
                stepOutputs(parts.drop(2), context)
            }

            else -> {
                throw AssertionError("unsupported icon-image expression: $expression")
            }
        }
    }

    private fun concat(
        parts: List<JsonElement>,
        context: Map<String, Set<String>>,
    ): Set<String> {
        var values = setOf("")
        for (part in parts) {
            val next =
                if (part is JsonArray && part.firstOrNull()?.stringValue() == "length") {
                    context["length:shield_text"].orEmpty()
                } else {
                    setOf(part.stringValue().orEmpty())
                }
            values = values.flatMap { prefix -> next.map { prefix + it } }.toSet()
        }
        return values
    }

    /**
     * `match` pairs are labelled by the input expression. A `["get", "kind"]`
     * fallback emits every kind the filter admits that no explicit label
     * covers, so a mapping such as `station` -> `train_station` is honoured
     * instead of also asking for a `station` glyph.
     */
    private fun match(
        parts: JsonArray,
        context: Map<String, Set<String>>,
    ): Set<String> {
        val input = emit(parts.getOrNull(1) ?: return emptySet(), context)
        val pairs = parts.drop(2).dropLast(1)
        val labels =
            pairs
                .filterIndexed { index, _ -> index % 2 == 0 }
                .mapNotNull { it.stringValue() }
                .toSet()
        val fallback = parts.last()
        val fallbackValues =
            if (input.isEmpty()) null else input - labels
        val emitted = mutableSetOf<String>()
        for (index in pairs.indices step 2) {
            emitted += emit(pairs[index + 1], context)
        }
        emitted +=
            if (fallbackValues == null) {
                emit(fallback, context)
            } else {
                emit(fallback, context, fallbackValues)
            }
        return emitted
    }

    private fun caseOutputs(
        parts: List<JsonElement>,
        context: Map<String, Set<String>>,
    ): Set<String> {
        val outputs = mutableSetOf<String>()
        for (index in parts.indices) {
            val isBranch = index % 2 == 1
            val isFallback = index == parts.lastIndex && parts.size % 2 == 1
            if (isBranch || isFallback) outputs += emit(parts[index], context)
        }
        return outputs
    }

    private fun stepOutputs(
        parts: List<JsonElement>,
        context: Map<String, Set<String>>,
    ): Set<String> =
        parts
            .filterIndexed { index, _ -> index % 2 == 0 }
            .flatMap { emit(it, context) }
            .toSet()

    private fun JsonElement.stringValue(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
