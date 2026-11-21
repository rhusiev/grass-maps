package nl.r1a.grassmaps.app.devcheck

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmaps.plugin.api.model.CatalogRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsDumpTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun write(
        path: String,
        text: String,
    ) {
        File(folder.root, path).apply { parentFile!!.mkdirs() }.writeText(text)
    }

    @Test
    fun valuesAreDumpedAndSecretsAreRedacted() {
        write("settings/units.json", """{"distance":"km"}""")
        write("settings/raster.json", """{"url":"https://t.example/{z}.png?key=SECRETVALUE&a=1","api_key":"SECRETVALUE"}""")

        val dump = SettingsDump.dump(folder.root)

        assertEquals("km", dump["settings/units.json"]!!.jsonObject["distance"]!!.jsonPrimitive.content)
        assertFalse(dump.toString().contains("SECRETVALUE"))
    }

    @Test
    fun tokenAndHistoryFilesLeaveOnlyTheirShape() {
        write("settings/downloads.json", """{"token":"SECRETVALUE","region":"a"}""")
        write("history/search-history.json", """["home street","work"]""")

        val dump = SettingsDump.dump(folder.root)

        assertFalse(dump.toString().contains("SECRETVALUE"))
        assertFalse(dump.toString().contains("home street"))
        assertEquals(2, (dump["history/search-history.json"] as JsonObject)["entries"]!!.jsonPrimitive.content.toInt())
        assertEquals(JsonPrimitive("absent"), dump["settings/map-labels.json"])
    }

    @Test
    fun planPointsLieInsideTheBoundingBox() {
        val region = CatalogRegion(id = "r", version = "v", bytes = 1, bbox = listOf(24.0, 49.0, 25.0, 50.0))

        val points = pickPlanPoints(region)!!

        assertTrue(points.startLat in 49.0..50.0 && points.endLat in 49.0..50.0)
        assertTrue(points.startLon in 24.0..25.0 && points.endLon in 24.0..25.0)
        assertNotNull(points)
    }

    @Test
    fun noBoundingBoxGivesNoPoints() {
        assertNull(pickPlanPoints(CatalogRegion(id = "r", version = "v", bytes = 1)))
    }
}
