package nl.r1a.grassmap.plugins.favourites.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps `plugin.json` in step with [FavouritesUiContract]: six non-undoable
 * commands, one stream, and the ids the plugin registers at activation.
 */
class FavouritesUiManifestTest {
    @Test
    fun theManifestDeclaresTheCommandsAndTheListsStream() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject
        val contributes = manifest.getValue("contributes").jsonObject

        assertEquals(FavouritesUiContract.PLUGIN_ID, manifest.getValue("id").jsonPrimitive.content)
        assertEquals(
            "nl.r1a.grassmap.plugins.favourites.ui.FavouritesUiPlugin",
            manifest.getValue("implementation").jsonPrimitive.content,
        )
        val commands = contributes.getValue("commands").jsonArray.map { it.jsonObject }
        assertEquals(
            FavouritesUiContract.commands.map { it.value }.toSet(),
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
            assertTrue(
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
        assertEquals(FavouritesUiContract.LISTS_STREAM_ID, stream.getValue("id").jsonPrimitive.content)
        assertEquals(FavouritesUiContract.LISTS_VALUE_TYPE, stream.getValue("valueType").jsonPrimitive.content)
        assertEquals(FavouritesUiContract.LISTS_SERIALIZER, stream.getValue("serializer").jsonPrimitive.content)
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
