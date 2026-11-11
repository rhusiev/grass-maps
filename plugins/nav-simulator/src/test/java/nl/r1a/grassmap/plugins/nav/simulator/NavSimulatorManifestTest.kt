package nl.r1a.grassmap.plugins.nav.simulator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Keeps `plugin.json` in step with [NavSimulatorContract]. */
class NavSimulatorManifestTest {
    @Test
    fun theManifestDeclaresTheCommandsAndTheStatusStream() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject
        val contributions = manifest.getValue("contributes").jsonObject

        assertEquals(NavSimulatorContract.PLUGIN_ID, manifest.getValue("id").jsonPrimitive.content)
        assertEquals(
            "nl.r1a.grassmap.plugins.nav.simulator.NavSimulatorPlugin",
            manifest.getValue("implementation").jsonPrimitive.content,
        )
        val commands = contributions.getValue("commands").jsonArray.map { it.jsonObject }
        assertEquals(
            NavSimulatorContract.commands.map { it.value }.toSet(),
            commands.map { it.getValue("id").jsonPrimitive.content }.toSet(),
        )
        for (command in commands) {
            assertEquals("kotlinx.serialization.json.JsonElement", command.getValue("params").jsonPrimitive.content)
            assertEquals(NavSimulatorContract.STATUS_VALUE_TYPE, command.getValue("result").jsonPrimitive.content)
            assertEquals(false, command.getValue("undoable").jsonPrimitive.boolean)
            assertTrue(command.getValue("allowlistedForControl").jsonPrimitive.boolean)
            assertEquals("io", command.getValue("dispatcher").jsonPrimitive.content)
            assertTrue(
                command
                    .getValue("implementation")
                    .jsonPrimitive.content
                    .isNotBlank(),
            )
        }

        val stream =
            contributions
                .getValue("streams")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(NavSimulatorContract.STATUS_STREAM_ID, stream.getValue("id").jsonPrimitive.content)
        assertEquals(NavSimulatorContract.STATUS_VALUE_TYPE, stream.getValue("valueType").jsonPrimitive.content)
        assertEquals(NavSimulatorContract.STATUS_SERIALIZER, stream.getValue("serializer").jsonPrimitive.content)
    }

    @Test
    fun theManifestContributesNoProviderOrWidgetAndNeedsNoLocation() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject
        val contributions = manifest.getValue("contributes").jsonObject

        assertEquals(setOf("commands", "streams"), contributions.keys)
        val needs = manifest.getValue("needs").jsonObject
        assertEquals("none", needs.getValue("location").jsonPrimitive.content)
        assertEquals(
            emptySet<String>(),
            needs
                .getValue("network")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet(),
        )
        assertEquals(
            emptySet<String>(),
            needs
                .getValue("regions")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet(),
        )
        assertEquals(
            setOf("command", "stream"),
            manifest
                .getValue("activation")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet(),
        )
    }

    private companion object {
        const val MANIFEST = "src/main/plugin.json"
    }
}
