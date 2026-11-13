package nl.r1a.grassmap.regions

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.Route
import nl.r1a.grassmap.plugin.api.model.RouteNetwork
import nl.r1a.grassmap.plugin.api.model.RouteRelationsJson
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The committed Lviv cycling-relation probe, extracted from the real Ukraine
 * PBF (T-0419). The source snapshot has bicycle routes but no `*cn_ref`
 * node-network junctions: the probe proves the real extraction path for
 * cycling and records that data gap in `provenance.json`.
 */
class LvivCyclingRelationsTest {
    private val directory = TestRegions.repositoryRoot().resolve("test-data/regions/lviv-cycling-relations")

    @Test
    fun theProbeCarriesRealExtractedCyclingRelations() =
        runTest {
            val text = Files.readString(directory.resolve("relations.json"))
            val component =
                when (val outcome = RouteRelationsJson.decode(text)) {
                    is Outcome.Success -> outcome.value
                    is Outcome.Failure -> error("probe unreadable: ${outcome.error}")
                }

            assertEquals(8, component.relations.size)
            assertEquals(402, component.ways.size)
            assertEquals(0, component.nodes.size)
            assertTrue(component.relations.all { it.route == Route.Cycling })
            assertEquals(
                mapOf(RouteNetwork.CyclingLocal to 3, RouteNetwork.CyclingRegional to 5),
                component.relations.groupingBy { it.network }.eachCount(),
            )
            val local = component.relations.first { it.id == 17634258L }
            assertEquals(RouteNetwork.CyclingLocal, local.network)
            assertEquals("4901", local.ref)
            assertEquals(12, local.members.size)
        }

    @Test
    fun theProvenanceRecordsTheSourceAndTheNodeNumberGap() {
        val provenance =
            Json
                .parseToJsonElement(Files.readString(directory.resolve("provenance.json")))
                .jsonObject
        val source = provenance.getValue("source").jsonObject
        val probe = provenance.getValue("probe").jsonObject
        val features = probe.getValue("features").jsonObject

        assertEquals(
            "ff46165a08b21fc4271aa6d5f199e55d0c9b786bcfd8f50e0f7c31f82248347f",
            source.getValue("sha256").jsonPrimitive.content,
        )
        assertEquals(
            "osm-extract 0.1.0 route-relations v2",
            provenance.getValue("extraction").jsonPrimitive.content,
        )
        assertEquals(
            8,
            features
                .getValue("relations")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            402,
            features
                .getValue("ways")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            0,
            features
                .getValue("nodes")
                .jsonPrimitive.content
                .toInt(),
        )
        assertTrue(
            probe
                .getValue("sha256")
                .jsonPrimitive.content.length == 64,
        )
        assertTrue(
            provenance
                .getValue("note")
                .jsonPrimitive.content
                .contains("*cn_ref"),
        )
    }
}
