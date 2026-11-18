package nl.r1a.grassmap.plugins.profiles.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmap.plugin.api.model.ProfilesState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Keeps `plugin.json` in step with [ProfilesUiContract]: the four commands and
 * the one state stream the plugin registers at activation (T-0602).
 */
class ProfilesUiManifestTest {
    @Test
    fun theManifestDeclaresTheCommandsAndTheStateStream() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject
        val contributes = manifest.getValue("contributes").jsonObject

        assertEquals(ProfilesUiContract.PLUGIN_ID, manifest.getValue("id").jsonPrimitive.content)
        assertEquals(
            "nl.r1a.grassmap.plugins.profiles.ui.ProfilesUiPlugin",
            manifest.getValue("implementation").jsonPrimitive.content,
        )
        val commands = contributes.getValue("commands").jsonArray.map { it.jsonObject }
        assertEquals(
            ProfilesUiContract.commands.map { it.value }.toSet(),
            commands.map { it.getValue("id").jsonPrimitive.content }.toSet(),
        )
        for (command in commands) {
            val id = command.getValue("id").jsonPrimitive.content
            assertEquals("$id undoable", "false", command.getValue("undoable").jsonPrimitive.content)
            assertEquals("$id control", "true", command.getValue("allowlistedForControl").jsonPrimitive.content)
            assertEquals("$id dispatcher", "io", command.getValue("dispatcher").jsonPrimitive.content)
        }

        val stream = contributes.getValue("streams").jsonArray.single().jsonObject
        assertEquals(ProfilesUiContract.STATE_STREAM_ID, stream.getValue("id").jsonPrimitive.content)
        assertEquals(ProfilesUiContract.STATE_VALUE_TYPE, stream.getValue("valueType").jsonPrimitive.content)
        assertEquals(ProfilesUiContract.STATE_SERIALIZER, stream.getValue("serializer").jsonPrimitive.content)
        assertEquals(ProfilesUiContract.STATE_VALUE_TYPE, ProfilesState.serializer().descriptor.serialName)
    }

    @Test
    fun theManifestContributesOnlyCommandsAndStreams() {
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
