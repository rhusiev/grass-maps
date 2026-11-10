package nl.r1a.grassmap.plugins.direct

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps `plugin.json` honest against the engine it ships. The manifest is what
 * the host reads before it resolves a provider, so a capability that is true
 * there and false in the engine is a lie the host acts on.
 */
class DirectManifestTest {
    @Test
    fun theManifestDeclaresTheEngineItShips() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject

        assertEquals("direct", manifest.getValue("id").jsonPrimitive.content)
        assertEquals(
            "nl.r1a.grassmap.plugins.direct.DirectPlugin",
            manifest.getValue("implementation").jsonPrimitive.content,
        )
        assertEquals(listOf("provider"), manifest.getValue("activation").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            emptyList<String>(),
            manifest
                .getValue("needs")
                .jsonObject
                .getValue("network")
                .jsonArray
                .map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun theDeclaredCapabilitiesMatchTheEngine() {
        val provider = providerOf()
        val capabilities = provider.getValue("capabilities").jsonObject
        val engine = DirectRoutingEngine().capabilities()

        assertEquals(RoutingEngineContract.NAME, provider.getValue("contract").jsonPrimitive.content)
        assertEquals(RoutingEngineContract.REFERENCE.version, provider.getValue("version").jsonPrimitive.content)
        assertEquals(
            engine.profiles.map { it.name.lowercase() }.toSet(),
            capabilities
                .getValue("profiles")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet(),
        )
        assertEquals(0, capabilities.getValue("attributes").jsonArray.size)
        assertTrue(engine.attributeKeys.isEmpty())
        assertFalse(capabilities.getValue("hasMatchTrack").jsonPrimitive.boolean)
        assertFalse(engine.hasMatchTrack)
    }

    private fun providerOf() =
        Json
            .parseToJsonElement(File(MANIFEST).readText())
            .jsonObject
            .getValue("contributes")
            .jsonObject
            .getValue("providers")
            .jsonArray
            .single()
            .jsonObject

    private companion object {
        const val MANIFEST = "src/main/plugin.json"
    }
}
