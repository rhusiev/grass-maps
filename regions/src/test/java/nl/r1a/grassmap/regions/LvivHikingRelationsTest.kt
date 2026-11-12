package nl.r1a.grassmap.regions

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.HikingNetwork
import nl.r1a.grassmap.plugin.api.model.RouteRelationsJson
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The committed Lviv hiking-relation probe, extracted from the real Ukraine
 * PBF (T-0418). `provenance.json` records the source digest, the whole-pack
 * counts and the probe box; the pack manifest beside it is the pack this task
 * built under /hdd.
 */
class LvivHikingRelationsTest {
    private val directory = TestRegions.repositoryRoot().resolve("test-data/regions/lviv-hiking-relations")

    @Test
    fun theProbeCarriesRealExtractedRelations() {
        val text = Files.readString(directory.resolve("relations.json"))
        val component =
            when (val outcome = RouteRelationsJson.decode(text)) {
                is Outcome.Success -> outcome.value
                is Outcome.Failure -> error("probe unreadable: ${outcome.error}")
            }

        assertEquals(6, component.relations.size)
        assertEquals(61, component.ways.size)
        assertEquals(
            listOf(
                4868853L,
                17975954L,
                19709971L,
                19839379L,
                19839382L,
                19839384L,
            ),
            component.relations.map { it.id },
        )
        assertTrue(component.relations.all { it.network == HikingNetwork.Local })
        val doob = component.relations.first { it.id == 19709971L }
        assertEquals("№501 Сколе – Скелі Довбуша", doob.name)
        assertEquals("orange:white:orange_bar:501:black", doob.osmcSymbol)
        assertEquals(37, doob.members.size)
    }

    @Test
    fun theProvenanceRecordsTheWholePackAndTheProbe() {
        val provenance =
            Json
                .parseToJsonElement(Files.readString(directory.resolve("provenance.json")))
                .jsonObject
        val source = provenance.getValue("source").jsonObject
        val features = provenance.getValue("features").jsonObject
        val probe = provenance.getValue("probe").jsonObject

        assertEquals(
            "ff46165a08b21fc4271aa6d5f199e55d0c9b786bcfd8f50e0f7c31f82248347f",
            source.getValue("sha256").jsonPrimitive.content,
        )
        assertEquals(
            87,
            features
                .getValue("relations")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            2360,
            features
                .getValue("ways")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            6,
            probe
                .getValue("features")
                .jsonObject
                .getValue("relations")
                .jsonPrimitive.content
                .toInt(),
        )
        assertTrue(
            probe
                .getValue("sha256")
                .jsonPrimitive.content.length == 64,
        )
    }

    @Test
    fun thePackManifestCarriesTheRelationsComponentAndItsSource() {
        val manifest =
            RegionManifestParser.parse(
                Files.readAllBytes(directory.resolve("pack-manifest.json")),
                "ua-46",
                "2026-11-12-1",
            ) as? Outcome.Success ?: error("pack manifest did not parse")

        val component = manifest.value.components.single { it.kind == "relations" }
        assertEquals("relations.json", component.file)
        assertEquals("route-relations-json", component.format)
        assertEquals(1_049_166L, component.bytes)
        val source =
            manifest.value.pipeline
                ?.sources
                ?.single { it.role == "routeRelationSource" }
        assertEquals("ukraine-latest.osm.pbf", source?.archive)
        assertEquals("ff46165a08b21fc4271aa6d5f199e55d0c9b786bcfd8f50e0f7c31f82248347f", source?.sha256)
    }
}
