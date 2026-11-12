package nl.r1a.grassmap.regions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The committed Lviv way-alert probe, extracted from the real Ukraine PBF
 * (T-0319A). The file is the output of `osm-extract --way-alerts` over a small
 * central-Lviv box, not an authored list; `provenance.json` records the source
 * digest, the full pack counts and the probe box.
 */
class LvivWayAlertsTest {
    private val directory = TestRegions.repositoryRoot().resolve("test-data/regions/lviv-way-alerts")
    private val document =
        Json
            .parseToJsonElement(String(Files.readAllBytes(directory.resolve("way-alerts.geojson")), Charsets.UTF_8))
            .jsonObject
    private val features = document.getValue("features").jsonArray.map { it.jsonObject }

    @Test
    fun theProbeCarriesTheExtractedKindsAndCounts() {
        val kinds =
            features
                .groupingBy {
                    it
                        .properties()
                        .getValue("kind")
                        .jsonPrimitive.content
                }.eachCount()

        assertEquals(135, features.size)
        assertEquals(45, kinds["railway_crossing"])
        assertEquals(22, kinds["tunnel"])
        assertEquals(2, kinds["hazard"])
        assertEquals(0, kinds["ford"] ?: 0)
        assertEquals(66, kinds["barrier"])
    }

    @Test
    fun aSpecificCrossingATunnelAndAHazardAreInTheProbe() {
        val crossing = feature("railway_crossing", 30508730L)
        assertEquals(
            "node",
            crossing
                .properties()
                .getValue("osmType")
                .jsonPrimitive.content,
        )
        assertEquals(
            "Point",
            crossing
                .getValue("geometry")
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content,
        )
        assertEquals(
            listOf(24.0228514, 49.8432638),
            crossing
                .getValue("geometry")
                .jsonObject
                .getValue("coordinates")
                .jsonArray
                .map { it.jsonPrimitive.double },
        )

        val tunnel = feature("tunnel", 770843895L)
        assertEquals(
            "way",
            tunnel
                .properties()
                .getValue("osmType")
                .jsonPrimitive.content,
        )
        assertEquals(
            "LineString",
            tunnel
                .getValue("geometry")
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content,
        )
        assertEquals(
            2,
            tunnel
                .getValue("geometry")
                .jsonObject
                .getValue("coordinates")
                .jsonArray.size,
        )

        val hazard = feature("hazard", 160911208L)
        assertEquals(
            "way",
            hazard
                .properties()
                .getValue("osmType")
                .jsonPrimitive.content,
        )
        assertEquals(
            11,
            hazard
                .getValue("geometry")
                .jsonObject
                .getValue("coordinates")
                .jsonArray.size,
        )
    }

    @Test
    fun theProvenanceRecordsTheFullPackRun() {
        val provenance =
            Json
                .parseToJsonElement(String(Files.readAllBytes(directory.resolve("provenance.json")), Charsets.UTF_8))
                .jsonObject
        val source = provenance.getValue("source").jsonObject
        val counts = provenance.getValue("features").jsonObject

        assertEquals("ukraine-latest.osm.pbf", source.getValue("archive").jsonPrimitive.content)
        assertEquals(
            "ff46165a08b21fc4271aa6d5f199e55d0c9b786bcfd8f50e0f7c31f82248347f",
            source.getValue("sha256").jsonPrimitive.content,
        )
        assertEquals(3343, counts.getValue("railway_crossing").jsonPrimitive.long)
        assertEquals(680, counts.getValue("tunnel").jsonPrimitive.long)
        assertEquals(51, counts.getValue("hazard").jsonPrimitive.long)
        assertEquals(4109, counts.getValue("ford").jsonPrimitive.long)
        assertEquals(9826, counts.getValue("barrier").jsonPrimitive.long)
        assertTrue(features.isNotEmpty())
    }

    private fun feature(
        kind: String,
        osmId: Long,
    ) = features.single {
        val properties = it.properties()
        properties.getValue("kind").jsonPrimitive.content == kind &&
            properties.getValue("osmId").jsonPrimitive.long == osmId
    }

    private fun kotlinx.serialization.json.JsonObject.properties() = getValue("properties").jsonObject
}
