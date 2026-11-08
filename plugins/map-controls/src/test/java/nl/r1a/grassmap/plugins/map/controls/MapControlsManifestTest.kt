package nl.r1a.grassmap.plugins.map.controls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Keeps `plugin.json` in step with [MapControlsContract]. The manifest lint runs
 * in the gate as well, but this test names the stream, the setting and its
 * profile scope that the plugin actually relies on.
 */
class MapControlsManifestTest {
    @Test
    fun theManifestDeclaresTheModeStreamAndTheProfileOffsetSetting() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject
        val contributions = manifest.getValue("contributes").jsonObject

        assertEquals(MapControlsContract.PLUGIN_ID.value, manifest.getValue("id").jsonPrimitive.content)
        assertStream(
            contributions
                .getValue("streams")
                .jsonArray
                .single()
                .jsonObject,
        )
        assertSetting(
            contributions
                .getValue("settings")
                .jsonArray
                .single()
                .jsonObject,
        )
    }

    private fun assertStream(stream: JsonObject) {
        assertEquals(MapControlsContract.MODE_STREAM_ID.value, stream.getValue("id").jsonPrimitive.content)
        assertEquals(MapControlsContract.MODE_VALUE_TYPE, stream.getValue("valueType").jsonPrimitive.content)
        assertEquals(MapControlsContract.MODE_SERIALIZER, stream.getValue("serializer").jsonPrimitive.content)
    }

    private fun assertSetting(setting: JsonObject) {
        assertEquals(
            MapControlsContract.POSITION_OFFSET_SETTING,
            setting.getValue("key").jsonPrimitive.content,
        )
        assertEquals("profile", setting.getValue("scope").jsonPrimitive.content)
        assertEquals("0.0", setting.getValue("default").jsonPrimitive.content)
    }

    private companion object {
        const val MANIFEST = "src/main/plugin.json"
    }
}
