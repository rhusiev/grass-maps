package nl.r1a.grassmap.plugins.point.info

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Keeps `plugin.json` in step with [PointInfoContract]. The manifest lint runs
 * in the gate as well, but this test names the stream, the widget and the value
 * type the plugin actually registers.
 */
class PointInfoManifestTest {
    @Test
    fun theManifestDeclaresTheSelectionStreamAndWidget() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject
        val contributions = manifest.getValue("contributes").jsonObject

        assertEquals(PointInfoContract.PLUGIN_ID, manifest.getValue("id").jsonPrimitive.content)
        assertEquals(
            "nl.r1a.grassmap.plugins.point.info.PointInfoPlugin",
            manifest.getValue("implementation").jsonPrimitive.content,
        )
        assertStream(
            contributions
                .getValue("streams")
                .jsonArray
                .single()
                .jsonObject,
        )
        assertWidget(
            contributions
                .getValue("widgets")
                .jsonArray
                .single()
                .jsonObject,
        )
    }

    private fun assertStream(stream: JsonObject) {
        assertEquals(PointInfoContract.SELECTION_STREAM_ID, stream.getValue("id").jsonPrimitive.content)
        assertEquals(PointInfoContract.SELECTION_VALUE_TYPE, stream.getValue("valueType").jsonPrimitive.content)
        assertEquals(
            "nl.r1a.grassmap.plugins.point.info.SelectedPointValue.serializer",
            stream.getValue("serializer").jsonPrimitive.content,
        )
    }

    private fun assertWidget(widget: JsonObject) {
        assertEquals(PointInfoContract.SELECTION_WIDGET_ID, widget.getValue("id").jsonPrimitive.content)
        assertEquals(PointInfoContract.SELECTION_TITLE, widget.getValue("title").jsonPrimitive.content)
        assertEquals(PointInfoContract.SELECTION_VALUE_TYPE, widget.getValue("valueType").jsonPrimitive.content)
        assertEquals(PointInfoContract.SELECTION_STREAM_ID, widget.getValue("streamId").jsonPrimitive.content)
        assertEquals(
            PointInfoContract.UPDATE_INTERVAL_MILLIS,
            widget
                .getValue("updateIntervalMillis")
                .jsonPrimitive.content
                .toLong(),
        )
    }

    private companion object {
        const val MANIFEST = "src/main/plugin.json"
    }
}
