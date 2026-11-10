package nl.r1a.grassmap.plugins.route.planner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Keeps `plugin.json` in step with [RoutePlannerContract] (T-0302). */
class RoutePlannerManifestTest {
    @Test
    fun theManifestDeclaresTheCommandsAndTheStateStream() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject
        val contributes = manifest.getValue("contributes").jsonObject

        assertEquals(RoutePlannerContract.PLUGIN_ID, manifest.getValue("id").jsonPrimitive.content)
        assertEquals(
            "nl.r1a.grassmap.plugins.route.planner.RoutePlannerPlugin",
            manifest.getValue("implementation").jsonPrimitive.content,
        )
        val commands = contributes.getValue("commands").jsonArray.map { it.jsonObject }
        assertEquals(
            RoutePlannerContract.commands.map { it.value }.toSet(),
            commands.map { it.getValue("id").jsonPrimitive.content }.toSet(),
        )
        for (command in commands) {
            assertEquals(
                false,
                command
                    .getValue("undoable")
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            assertEquals(
                true,
                command
                    .getValue("allowlistedForControl")
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            assertEquals("io", command.getValue("dispatcher").jsonPrimitive.content)
            assertTrue(
                command
                    .getValue("implementation")
                    .jsonPrimitive.content
                    .isNotBlank(),
            )
        }

        val stream =
            contributes
                .getValue("streams")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(RoutePlannerContract.STATE_STREAM_ID, stream.getValue("id").jsonPrimitive.content)
        assertEquals(RoutePlannerContract.STATE_VALUE_TYPE, stream.getValue("valueType").jsonPrimitive.content)
        assertEquals(RoutePlannerContract.STATE_SERIALIZER, stream.getValue("serializer").jsonPrimitive.content)
    }

    @Test
    fun theManifestContributesNoProviderOrWidget() {
        val contributes =
            Json
                .parseToJsonElement(File(MANIFEST).readText())
                .jsonObject
                .getValue("contributes")
                .jsonObject

        assertEquals(setOf("commands", "streams"), contributes.keys)
    }

    private companion object {
        const val MANIFEST = "src/main/plugin.json"
    }
}
