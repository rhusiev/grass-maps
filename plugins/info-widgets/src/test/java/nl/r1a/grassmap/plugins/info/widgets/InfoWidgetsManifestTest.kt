package nl.r1a.grassmap.plugins.info.widgets

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Keeps `plugin.json` in step with [InfoWidgetsContract]. The host rejects a
 * mismatch at activation, but the app does not bind these widgets until T-0605,
 * so a unit test is the check that runs today.
 */
class InfoWidgetsManifestTest {
    @Test
    fun theManifestDeclaresTheContractIdsTitlesAndCadence() {
        val manifest = Json.parseToJsonElement(File(MANIFEST).readText()).jsonObject
        val contributions = manifest.getValue("contributes").jsonObject

        assertEquals(InfoWidgetsContract.PLUGIN_ID, manifest.getValue("id").jsonPrimitive.content)
        assertWidgets(contributions.getValue("widgets").jsonArray.map { it.jsonObject })
        assertStreams(contributions.getValue("streams").jsonArray.map { it.jsonObject })
    }

    private fun assertWidgets(widgets: List<JsonObject>) {
        val byId = widgets.associateBy { it.getValue("id").jsonPrimitive.content }
        assertEquals(setOf(ALTITUDE, COORDINATES, GPS, SPEED_CURRENT, SPEED_AVERAGE, SPEED_MAX), byId.keys)
        assertWidget(byId.getValue(ALTITUDE), InfoWidgetsContract.ALTITUDE_TITLE, InfoWidgetsContract.ALTITUDE_VALUE_TYPE)
        assertWidget(
            byId.getValue(COORDINATES),
            InfoWidgetsContract.COORDINATES_TITLE,
            InfoWidgetsContract.COORDINATES_VALUE_TYPE,
        )
        assertWidget(byId.getValue(GPS), InfoWidgetsContract.GPS_TITLE, InfoWidgetsContract.GPS_VALUE_TYPE)
        assertWidget(
            byId.getValue(SPEED_CURRENT),
            InfoWidgetsContract.SPEED_CURRENT_TITLE,
            InfoWidgetsContract.SPEED_VALUE_TYPE,
        )
        assertWidget(
            byId.getValue(SPEED_AVERAGE),
            InfoWidgetsContract.SPEED_AVERAGE_TITLE,
            InfoWidgetsContract.SPEED_VALUE_TYPE,
        )
        assertWidget(
            byId.getValue(SPEED_MAX),
            InfoWidgetsContract.SPEED_MAX_TITLE,
            InfoWidgetsContract.SPEED_VALUE_TYPE,
        )
    }

    private fun assertWidget(
        widget: JsonObject,
        title: String,
        valueType: String,
    ) {
        assertEquals(title, widget.getValue("title").jsonPrimitive.content)
        assertEquals(valueType, widget.getValue("valueType").jsonPrimitive.content)
        assertEquals(widget.getValue("id").jsonPrimitive.content, widget.getValue("streamId").jsonPrimitive.content)
        assertEquals(
            InfoWidgetsContract.UPDATE_INTERVAL_MILLIS.toInt(),
            widget.getValue("updateIntervalMillis").jsonPrimitive.int,
        )
    }

    private fun assertStreams(streams: List<JsonObject>) {
        val byId = streams.associateBy { it.getValue("id").jsonPrimitive.content }
        assertEquals(setOf(ALTITUDE, COORDINATES, GPS, SPEED_CURRENT, SPEED_AVERAGE, SPEED_MAX), byId.keys)
        assertEquals(
            InfoWidgetsContract.ALTITUDE_VALUE_TYPE,
            byId
                .getValue(ALTITUDE)
                .getValue("valueType")
                .jsonPrimitive.content,
        )
        assertEquals(
            InfoWidgetsContract.COORDINATES_VALUE_TYPE,
            byId
                .getValue(COORDINATES)
                .getValue("valueType")
                .jsonPrimitive.content,
        )
        assertEquals(
            InfoWidgetsContract.GPS_VALUE_TYPE,
            byId
                .getValue(GPS)
                .getValue("valueType")
                .jsonPrimitive.content,
        )
        listOf(SPEED_CURRENT, SPEED_AVERAGE, SPEED_MAX).forEach { id ->
            assertEquals(
                InfoWidgetsContract.SPEED_VALUE_TYPE,
                byId
                    .getValue(id)
                    .getValue("valueType")
                    .jsonPrimitive.content,
            )
        }
    }

    private companion object {
        const val MANIFEST = "src/main/plugin.json"
        const val ALTITUDE = InfoWidgetsContract.ALTITUDE_STREAM_ID
        const val COORDINATES = InfoWidgetsContract.COORDINATES_STREAM_ID
        const val GPS = InfoWidgetsContract.GPS_STREAM_ID
        const val SPEED_CURRENT = InfoWidgetsContract.SPEED_CURRENT_STREAM_ID
        const val SPEED_AVERAGE = InfoWidgetsContract.SPEED_AVERAGE_STREAM_ID
        const val SPEED_MAX = InfoWidgetsContract.SPEED_MAX_STREAM_ID
    }
}
