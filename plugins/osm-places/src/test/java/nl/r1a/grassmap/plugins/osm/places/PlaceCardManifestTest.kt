package nl.r1a.grassmap.plugins.osm.places

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Keeps `plugin.json` in step with the plugin. The manifest lint runs in the
 * gate as well; this test names the provider contract the plugin registers.
 */
class PlaceCardManifestTest {
    @Test
    fun theManifestDeclaresOnePlacesCardProvider() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject

        assertEquals("osm.places", manifest.getValue("id").jsonPrimitive.content)
        assertEquals(
            "nl.r1a.grassmap.plugins.osm.places.OsmPlacesPlugin",
            manifest.getValue("implementation").jsonPrimitive.content,
        )
        assertProvider(
            manifest
                .getValue("contributes")
                .jsonObject
                .getValue("providers")
                .jsonArray
                .single()
                .jsonObject,
        )
        assertEquals(
            "none",
            manifest
                .getValue("needs")
                .jsonObject
                .getValue("location")
                .jsonPrimitive.content,
        )
        assertEquals(
            "provider",
            manifest
                .getValue("activation")
                .jsonArray
                .single()
                .jsonPrimitive.content,
        )
    }

    @Test
    fun theManifestHasNoCommandOrUiContribution() {
        val contributes =
            Json
                .parseToJsonElement(File(MANIFEST).readText())
                .jsonObject
                .getValue("contributes")
                .jsonObject

        assertEquals(emptySet<String>(), contributes.keys - PROVIDER_KEY)
    }

    private fun assertProvider(provider: JsonObject) {
        assertEquals("places.card", provider.getValue("contract").jsonPrimitive.content)
        assertEquals("1.2.0", provider.getValue("version").jsonPrimitive.content)
    }

    private companion object {
        const val MANIFEST = "src/main/plugin.json"
        const val PROVIDER_KEY = "providers"
    }
}
