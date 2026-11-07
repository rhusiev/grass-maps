package nl.r1a.grassmap.plugins.dev.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps `plugin.json` in step with [DevToolsContract]. The manifest lint runs in
 * the gate as well, but this test names the ids, the switch and the stream type
 * the plugin actually registers.
 */
class DevToolsManifestTest {
    @Test
    fun theManifestDeclaresTheCommandsAndTheStateStream() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject
        val contributions = manifest.getValue("contributes").jsonObject

        assertEquals(DevToolsContract.PLUGIN_ID, manifest.getValue("id").jsonPrimitive.content)
        assertCommands(contributions.getValue("commands").jsonArray.map { it.jsonObject })
        assertStream(
            contributions
                .getValue("streams")
                .jsonArray
                .single()
                .jsonObject,
        )
    }

    private fun assertCommands(commands: List<JsonObject>) {
        val byId = commands.associateBy { it.getValue("id").jsonPrimitive.content }
        assertEquals(
            setOf(
                DevToolsContract.START_REPLAY_COMMAND_ID,
                DevToolsContract.STOP_REPLAY_COMMAND_ID,
            ),
            byId.keys,
        )
        byId.values.forEach { command ->
            assertEquals(
                DevToolsContract.aiControlSwitch.value,
                command.getValue("requiresSwitch").jsonPrimitive.content,
            )
            assertTrue(command.getValue("allowlistedForControl").jsonPrimitive.boolean)
            assertEquals("io", command.getValue("dispatcher").jsonPrimitive.content)
        }
    }

    private fun assertStream(stream: JsonObject) {
        assertEquals(DevToolsContract.STATE_STREAM_ID, stream.getValue("id").jsonPrimitive.content)
        assertEquals(DevToolsContract.STATE_VALUE_TYPE, stream.getValue("valueType").jsonPrimitive.content)
        assertEquals(DevToolsContract.STATE_SERIALIZER, stream.getValue("serializer").jsonPrimitive.content)
    }

    private companion object {
        const val MANIFEST = "src/main/plugin.json"
    }
}
