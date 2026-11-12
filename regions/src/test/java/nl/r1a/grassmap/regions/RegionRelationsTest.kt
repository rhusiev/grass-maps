package nl.r1a.grassmap.regions

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.HikingNetwork
import nl.r1a.grassmap.plugin.api.model.RouteRelations
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The region reader against the committed test region's hiking relations
 * (T-0418). The fixture component is the real extractor's output from the
 * fixture's own small OSM input; the extractor's Rust test pins the selection.
 */
class RegionRelationsTest {
    @Test
    fun theFixtureCarriesItsThreeHikingRelations() =
        runTest {
            val relations = relationsOf(TestRegions.load().root.toFile())

            assertEquals(listOf(9101L, 9102L, 9107L), relations.relations.map { it.id })
            assertEquals(listOf(2, 1, 1), relations.relations.map { it.members.size })
            assertEquals(
                listOf(
                    HikingNetwork.Local,
                    HikingNetwork.Regional,
                    HikingNetwork.National,
                ),
                relations.relations.map { it.network },
            )
            assertEquals(3, relations.ways.size)
        }

    @Test
    fun aRelationKeepsItsMemberOrderAndWayGeometry() =
        runTest {
            val relations = relationsOf(TestRegions.load().root.toFile())
            val first = relations.relations.first { it.id == 9101L }

            assertEquals(listOf(896757861L, 306496773L), first.members.map { it.wayId })
            assertEquals("Лісова Гряда", first.name)
            assertEquals("red:white:red_bar", first.osmcSymbol)
            assertEquals(
                2,
                relations.ways
                    .getValue(896757861L)
                    .size
                    .coerceAtMost(2),
            )
            assertTrue(relations.ways.getValue(306496773L).size > 10)
        }

    @Test
    fun aPackageWithoutTheComponentReturnsTheEmptyValue() =
        runTest {
            val directory = packageWith(null)
            try {
                assertEquals(
                    Outcome.Success(RouteRelations.EMPTY),
                    RegionReader(directory).routeRelations(),
                )
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun aCorruptRelationComponentIsCorruptData() =
        runTest {
            val directory = packageWith("""{"format":"grass-maps-route-relations","version":1}""")
            try {
                assertEquals(
                    Outcome.Failure(AppError.CorruptData),
                    RegionReader(directory).routeRelations(),
                )
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun aMemberNamingAnUnknownWayIsCorruptData() =
        runTest {
            val directory =
                packageWith(
                    """
                    {"format":"grass-maps-route-relations","version":1,
                     "ways":[{"id":1,"coordinates":[[23.94,49.94],[23.95,49.95]]}],
                     "relations":[{"id":9,"route":"hiking","members":[{"way":2,"role":""}]}]}
                    """.trimIndent(),
                )
            try {
                assertEquals(
                    Outcome.Failure(AppError.CorruptData),
                    RegionReader(directory).routeRelations(),
                )
            } finally {
                directory.deleteRecursively()
            }
        }

    private suspend fun relationsOf(directory: File): RouteRelations =
        when (val outcome = RegionReader(directory).routeRelations()) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> error("relations unreadable: ${outcome.error}")
        }

    /** A minimal package whose manifest declares the relations component. */
    private fun packageWith(componentText: String?): File {
        val root = Files.createTempDirectory("region-relations-").toFile()
        val directory = File(root, "fixture-mini/2026-11-03-1")
        directory.mkdirs()
        val features = File(directory, "features.bin")
        features.writeText("x")
        val featureComponent =
            """
            {"kind":"features","file":"features.bin","format":"place-features",
             "formatVersion":1,"version":"2026-11-03-1","bytes":${features.length()},
             "sha256":"${sha256(features)}","standIn":false}
            """.trimIndent()
        val components =
            if (componentText == null) {
                featureComponent
            } else {
                val file = File(directory, "relations.json")
                file.writeText(componentText)
                """
                $featureComponent,
                {"kind":"relations","file":"relations.json","format":"route-relations-json",
                 "formatVersion":1,"version":"2026-11-03-1","bytes":${file.length()},
                 "sha256":"${sha256(file)}","standIn":false}
                """.trimIndent()
            }
        File(directory, "manifest.json").writeText(
            """
            {"region":"fixture-mini","version":"2026-11-03-1","packageFormat":1,
             "osmTimestamp":"2026-11-03T21:24:23Z","components":[$components]}
            """.trimIndent(),
        )
        return directory
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }
}
