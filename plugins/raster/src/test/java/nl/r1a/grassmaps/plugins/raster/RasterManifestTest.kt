package nl.r1a.grassmaps.plugins.raster

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The plugin declares the one provider and the one switch it needs. */
class RasterManifestTest {
    @Test
    fun theManifestNamesTheProviderAndTheOnlineTilesSwitch() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject

        assertEquals("map.raster", manifest.getValue("id").jsonPrimitive.content)
        assertEquals(
            "nl.r1a.grassmaps.plugins.raster.RasterPlugin",
            manifest.getValue("implementation").jsonPrimitive.content,
        )
        val provider =
            manifest
                .getValue("contributes")
                .jsonObject
                .getValue("providers")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("map.layers", provider.getValue("contract").jsonPrimitive.content)
        val network =
            manifest
                .getValue("needs")
                .jsonObject
                .getValue("network")
                .jsonArray
        assertEquals(listOf("network.online_tiles"), network.map { it.jsonPrimitive.content })
        assertEquals(listOf("provider"), (manifest["activation"] as? JsonArray)?.map { it.jsonPrimitive.content })
    }

    @Test
    fun englishAndUkrainianCarryTheSamePluginStrings() {
        val english = resources(ENGLISH)
        val ukrainian = resources(UKRAINIAN)

        assertEquals(english.keys, ukrainian.keys)
        assertTrue(english.isNotEmpty())
        for ((key, value) in english + ukrainian) {
            assertTrue("$key is blank", value.isNotBlank())
        }
    }

    private fun resources(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val MANIFEST = "src/main/plugin.json"
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
    }
}
