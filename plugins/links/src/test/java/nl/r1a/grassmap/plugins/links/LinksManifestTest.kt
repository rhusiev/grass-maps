package nl.r1a.grassmap.plugins.links

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The links manifest declares the parser module and nothing it cannot do. */
class LinksManifestTest {
    @Test
    fun theManifestNamesThePluginImplementation() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject

        assertEquals("links", manifest.getValue("id").jsonPrimitive.content)
        assertEquals(
            "nl.r1a.grassmap.plugins.links.LinksPlugin",
            manifest.getValue("implementation").jsonPrimitive.content,
        )
        assertEquals("plugin.links.name", manifest.getValue("name").jsonPrimitive.content)
        assertEquals("plugin.links.description", manifest.getValue("description").jsonPrimitive.content)
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

    private companion object {
        const val MANIFEST = "src/main/plugin.json"
    }
}
