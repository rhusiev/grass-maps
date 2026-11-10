package nl.r1a.grassmap.plugins.coordinates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The coordinates manifest declares the parser module and nothing it cannot do. */
class CoordinatesManifestTest {
    @Test
    fun theManifestNamesThePluginImplementation() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject

        assertEquals("coordinates", manifest.getValue("id").jsonPrimitive.content)
        assertEquals(
            "nl.r1a.grassmap.plugins.coordinates.CoordinatesPlugin",
            manifest.getValue("implementation").jsonPrimitive.content,
        )
        assertEquals("plugin.coordinates.name", manifest.getValue("name").jsonPrimitive.content)
        assertEquals("plugin.coordinates.description", manifest.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun theManifestContributesNothingItCannotServe() {
        val contributes =
            Json
                .parseToJsonElement(File(MANIFEST).readText())
                .jsonObject
                .getValue("contributes")
                .jsonObject

        assertTrue("a parser needs no command, stream, widget or provider", contributes.isEmpty())
    }

    @Test
    fun thePluginNeedsNoNetworkLocationOrRegions() {
        val needs =
            Json
                .parseToJsonElement(File(MANIFEST).readText())
                .jsonObject
                .getValue("needs")
                .jsonObject

        assertEquals("none", needs.getValue("location").jsonPrimitive.content)
        assertTrue(needs.getValue("network").jsonArray.isEmpty())
        assertTrue(needs.getValue("regions").jsonArray.isEmpty())
    }

    @Test
    fun bothLanguagesCarryTheLabel() {
        val english = strings(ENGLISH)
        val ukrainian = strings(UKRAINIAN)

        assertEquals(KEYS, english.keys)
        assertEquals(english.keys, ukrainian.keys)
        for ((key, value) in english + ukrainian) {
            assertTrue("$key is blank", value.isNotBlank())
        }
    }

    private fun strings(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val MANIFEST = "src/main/plugin.json"
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
        val KEYS = setOf("plugin.coordinates.name", "plugin.coordinates.description")
    }
}
