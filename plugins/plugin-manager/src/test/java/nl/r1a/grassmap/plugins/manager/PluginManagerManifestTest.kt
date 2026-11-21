package nl.r1a.grassmaps.plugins.manager

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The manager manifest names the plugin and asks for nothing. */
class PluginManagerManifestTest {
    private val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject

    @Test
    fun theManifestNamesThePluginImplementation() {
        assertEquals("plugin.manager", manifest.getValue("id").jsonPrimitive.content)
        assertEquals(
            "nl.r1a.grassmap.plugins.manager.PluginManagerPlugin",
            manifest.getValue("implementation").jsonPrimitive.content,
        )
        assertEquals("plugin.manager.name", manifest.getValue("name").jsonPrimitive.content)
        assertEquals("plugin.manager.description", manifest.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun theManifestContributesAndNeedsNothing() {
        val needs = manifest.getValue("needs").jsonObject

        assertTrue(manifest.getValue("contributes").jsonObject.isEmpty())
        assertEquals("none", needs.getValue("location").jsonPrimitive.content)
        assertTrue(needs.getValue("network").jsonArray.isEmpty())
        assertTrue(needs.getValue("regions").jsonArray.isEmpty())
    }

    private companion object {
        const val MANIFEST = "src/main/plugin.json"
    }
}
