package nl.r1a.grassmap.plugins.gpx

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Keeps the `file.gpx` manifest's export capability in step with T-0410. */
class GpxManifestTest {
    @Test
    fun theManifestAdvertisesGpxExport() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject
        val provider =
            manifest
                .getValue("contributes")
                .jsonObject
                .getValue("providers")
                .jsonArray
                .single()
                .jsonObject
        val capabilities = provider.getValue("capabilities").jsonObject

        assertEquals("file.format", provider.getValue("contract").jsonPrimitive.content)
        assertEquals(listOf("gpx"), capabilities.getValue("extensions").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            listOf("application/gpx+xml", "application/xml"),
            capabilities.getValue("mimeTypes").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("track", "route", "waypoint"),
            capabilities.getValue("entityKinds").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(capabilities.getValue("export").jsonPrimitive.boolean)
    }

    private companion object {
        const val MANIFEST = "src/main/plugin.json"
    }
}
