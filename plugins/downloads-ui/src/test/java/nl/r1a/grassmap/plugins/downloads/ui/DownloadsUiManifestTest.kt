package nl.r1a.grassmap.plugins.downloads.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmap.plugin.api.model.DownloadsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps `plugin.json` in step with [DownloadsUiContract]: the six commands and
 * the one state stream the plugin registers at activation (T-0504).
 */
class DownloadsUiManifestTest {
    @Test
    fun theManifestDeclaresTheCommandsAndTheStateStream() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject
        val contributes = manifest.getValue("contributes").jsonObject

        assertEquals(DownloadsUiContract.PLUGIN_ID, manifest.getValue("id").jsonPrimitive.content)
        assertEquals(
            "nl.r1a.grassmap.plugins.downloads.ui.DownloadsUiPlugin",
            manifest.getValue("implementation").jsonPrimitive.content,
        )
        val commands = contributes.getValue("commands").jsonArray.map { it.jsonObject }
        assertEquals(
            DownloadsUiContract.commands.map { it.value }.toSet(),
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
            assertEquals(
                "$id dispatcher",
                if (id == DownloadsUiContract.SELECT_REGION) "default" else "io",
                command.getValue("dispatcher").jsonPrimitive.content,
            )
            assertTrue(
                "$id implementation",
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
        assertEquals(DownloadsUiContract.STATE_STREAM_ID, stream.getValue("id").jsonPrimitive.content)
        assertEquals(DownloadsUiContract.STATE_VALUE_TYPE, stream.getValue("valueType").jsonPrimitive.content)
        assertEquals(DownloadsUiContract.STATE_SERIALIZER, stream.getValue("serializer").jsonPrimitive.content)
        assertEquals(DownloadsUiContract.STATE_VALUE_TYPE, DownloadsState.serializer().descriptor.serialName)
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

    @Test
    fun theManifestNeedsTheRegionUpdateSwitchAndForegroundLocation() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject
        val needs = manifest.getValue("needs").jsonObject

        assertEquals(
            listOf("network.region_updates"),
            needs.getValue("network").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("while_in_use", needs.getValue("location").jsonPrimitive.content)
        assertTrue(needs.getValue("regions").jsonArray.isEmpty())
    }

    @Test
    fun theManifestActivatesOnTheScreenCommandAndStream() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject

        assertEquals(
            setOf("command", "stream", "ui"),
            manifest
                .getValue("activation")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet(),
        )
    }

    private companion object {
        const val MANIFEST = "src/main/plugin.json"

        /** Every transfer command is control-allowlisted; opening a sheet is not. */
        val CONTROL_ALLOWED =
            setOf(
                DownloadsUiContract.REFRESH,
                DownloadsUiContract.DOWNLOAD_REGION,
                DownloadsUiContract.CANCEL_REGION,
                DownloadsUiContract.REMOVE_REGION,
                DownloadsUiContract.SET_SERVER,
                DownloadsUiContract.SET_MOBILE_DATA,
                DownloadsUiContract.USE_MOBILE_DATA_NOW,
            )
    }
}
