package nl.r1a.grassmap.plugins.track.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps `plugin.json` in step with [TrackLibraryContract]: the nine action and
 * view commands, one stream, and the ids the plugin registers at activation.
 */
class TrackLibraryManifestTest {
    @Test
    fun theManifestDeclaresEveryCommandAndTheStateStream() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject
        val contributes = manifest.getValue("contributes").jsonObject

        assertEquals(TrackLibraryContract.PLUGIN_ID, manifest.getValue("id").jsonPrimitive.content)
        assertEquals(
            "nl.r1a.grassmap.plugins.track.library.TrackLibraryPlugin",
            manifest.getValue("implementation").jsonPrimitive.content,
        )
        val commands = contributes.getValue("commands").jsonArray.map { it.jsonObject }
        assertEquals(
            TrackLibraryContract.commands.map { it.value }.toSet(),
            commands.map { it.getValue("id").jsonPrimitive.content }.toSet(),
        )
        for (command in commands) {
            val id = command.getValue("id").jsonPrimitive.content
            assertEquals(
                "$id undoable",
                false,
                command
                    .getValue("undoable")
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            assertEquals(
                "$id control allowlist",
                id in CONTROL_ALLOWED,
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
        assertEquals(TrackLibraryContract.STATE_STREAM_ID, stream.getValue("id").jsonPrimitive.content)
        assertEquals(TrackLibraryContract.STATE_VALUE_TYPE, stream.getValue("valueType").jsonPrimitive.content)
        assertEquals(TrackLibraryContract.STATE_SERIALIZER, stream.getValue("serializer").jsonPrimitive.content)
    }

    private companion object {
        const val MANIFEST = "src/main/plugin.json"
        val CONTROL_ALLOWED =
            setOf(
                TrackLibraryContract.CREATE_FOLDER,
                TrackLibraryContract.RENAME_FOLDER,
                TrackLibraryContract.EDIT_TRACK,
                TrackLibraryContract.MOVE_TRACK,
                TrackLibraryContract.DELETE_TRACK,
                TrackLibraryContract.UNDO,
            )
    }
}
