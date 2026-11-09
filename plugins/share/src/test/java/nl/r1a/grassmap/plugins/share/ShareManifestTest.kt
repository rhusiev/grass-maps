package nl.r1a.grassmap.plugins.share

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps `plugin.json` in step with [ShareContract]: four non-undoable,
 * control-allowlisted commands on the main dispatcher, and nothing else.
 */
class ShareManifestTest {
    @Test
    fun theManifestDeclaresTheShareCommands() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject
        val contributes = manifest.getValue("contributes").jsonObject

        assertEquals(ShareContract.PLUGIN_ID, manifest.getValue("id").jsonPrimitive.content)
        assertEquals(
            "nl.r1a.grassmap.plugins.share.SharePlugin",
            manifest.getValue("implementation").jsonPrimitive.content,
        )
        val commands = contributes.getValue("commands").jsonArray.map { it.jsonObject }
        assertEquals(
            ShareContract.commands.map { it.value }.toSet(),
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
            assertEquals("main", command.getValue("dispatcher").jsonPrimitive.content)
            assertTrue(
                command
                    .getValue("implementation")
                    .jsonPrimitive.content
                    .isNotBlank(),
            )
        }
    }

    @Test
    fun theManifestContributesNoProviderStreamOrWidget() {
        val contributes =
            Json
                .parseToJsonElement(File(MANIFEST).readText())
                .jsonObject
                .getValue("contributes")
                .jsonObject

        assertEquals(setOf("commands"), contributes.keys)
    }

    @Test
    fun thePluginAsksForTheForegroundLocationItShares() {
        val needs =
            Json
                .parseToJsonElement(File(MANIFEST).readText())
                .jsonObject
                .getValue("needs")
                .jsonObject

        assertEquals("while_in_use", needs.getValue("location").jsonPrimitive.content)
        assertTrue(needs.getValue("network").jsonArray.isEmpty())
    }

    private companion object {
        const val MANIFEST = "src/main/plugin.json"
    }
}
