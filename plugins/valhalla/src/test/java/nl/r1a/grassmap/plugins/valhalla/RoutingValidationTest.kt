package nl.r1a.grassmap.plugins.valhalla

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The committed result of the 100x3 offline validation (T-0301).
 *
 * The numbers come from `test-data/routing/` and the run that produced them is
 * described in its README. Three claims are checked against each other rather
 * than trusted: every pair the reference routed also routed here, every pair's
 * length delta is inside the committed tolerance, and the committed tolerance
 * is the worst delta of this run rounded up. A rerun that regresses any of them
 * fails the build.
 */
class RoutingValidationTest {
    @Test
    fun everyPairTheReferenceRoutesAlsoRoutesHere() {
        val rows = rows()
        for (profile in PROFILES) {
            val profileRows = rows.filter { it.profile == profile }
            assertEquals("$profile pair count", 100, profileRows.size)
            val referenceRouted = profileRows.filter { it.referenceOk }
            val missing = referenceRouted.filter { !it.valhallaOk }
            assertEquals("$profile pairs the engine is missing", emptyList<Int>(), missing.map { it.pair })
        }
    }

    @Test
    fun everyPairStaysInsideTheCommittedTolerance() {
        val tolerance =
            summary()
                .getValue("tolerance")
                .jsonObject
                .getValue("fraction")
                .jsonPrimitive.double
        val outside =
            rows()
                .filter { it.valhallaOk && it.referenceOk }
                .filter { abs(it.delta()) > tolerance }
                .map { "${it.profile} pair ${it.pair}: ${it.delta()}" }

        assertEquals("pairs outside $tolerance", emptyList<String>(), outside)
    }

    @Test
    fun theCommittedToleranceIsThisRunsWorstPairRoundedUp() {
        val deltas = rows().filter { it.valhallaOk && it.referenceOk }.map { abs(it.delta()) }
        val worst = deltas.max()
        val committed =
            summary()
                .getValue("tolerance")
                .jsonObject
                .getValue("fraction")
                .jsonPrimitive.double

        // The CSV rounds metres to 0.1, so the committed bound is the worst
        // delta rounded up to 0.001 and may differ by less than one step.
        assertTrue("committed $committed is below the run's worst $worst", committed >= worst - 1e-9)
        assertTrue("committed $committed is more than one step above $worst", committed <= worst + 0.001)
        assertEquals("pair and profile rows recorded", 300, deltas.size)
    }

    @Test
    fun everySegmentCarriesTheFiveAttributes() {
        val rows = rows()
        for (profile in PROFILES) {
            val profileRows = rows.filter { it.profile == profile }
            val segments = profileRows.sumOf { it.segments }
            assertTrue("$profile has no segments", segments > 0)
            for (key in listOf("surface", "highway", "sac_scale", "access")) {
                val coverage = profileRows.sumOf { it.count(key) }.toDouble() / segments
                assertTrue("$profile $key coverage $coverage", coverage >= 0.95)
            }
            // Grade is sampled from the region DEM, so a route leaving the
            // raster loses it; 95 % is the floor the run met.
            val grade = profileRows.sumOf { it.grade }.toDouble() / segments
            assertTrue("$profile grade coverage $grade", grade >= 0.95)
        }
    }

    @Test
    fun theRunNamesTheEnginesItUsed() {
        val engines = summary().getValue("engines").jsonObject

        assertTrue(
            engines
                .getValue("valhalla")
                .jsonPrimitive.content
                .startsWith("ghcr.io/valhalla/valhalla"),
        )
        assertTrue(
            engines
                .getValue("reference")
                .jsonPrimitive.content
                .contains("GraphHopper 11.1"),
        )
        assertTrue(
            engines
                .getValue("reference")
                .jsonPrimitive.content
                .contains("8462f758"),
        )
    }

    private fun rows(): List<Row> {
        val lines = resource("routing/lviv-routes.csv").trim().lines()
        val header = lines.first().split(',')
        return lines.drop(1).map { line ->
            val values = line.split(',')
            val row = header.zip(values).toMap()
            Row(
                pair = row.getValue("pair").toInt(),
                profile = row.getValue("profile"),
                valhallaOk = row.getValue("valhalla") == "ok",
                referenceOk = row.getValue("graphhopper") == "ok",
                valhallaMetres = row.getValue("valhalla_m").toDouble(),
                referenceMetres = row.getValue("graphhopper_m").toDouble(),
                segments = row.getValue("segments").toInt(),
                surface = row.getValue("surface").toInt(),
                highway = row.getValue("highway").toInt(),
                grade = row.getValue("grade").toInt(),
                sacScale = row.getValue("sac_scale").toInt(),
                access = row.getValue("access").toInt(),
            )
        }
    }

    private fun summary(): JsonObject = Json.parseToJsonElement(resource("routing/lviv-summary.json")).jsonObject

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "missing $name" }
            .bufferedReader()
            .use { it.readText() }

    private data class Row(
        val pair: Int,
        val profile: String,
        val valhallaOk: Boolean,
        val referenceOk: Boolean,
        val valhallaMetres: Double,
        val referenceMetres: Double,
        val segments: Int,
        val surface: Int,
        val highway: Int,
        val grade: Int,
        val sacScale: Int,
        val access: Int,
    ) {
        fun delta(): Double = (valhallaMetres - referenceMetres) / referenceMetres

        fun count(key: String): Int =
            when (key) {
                "surface" -> surface
                "highway" -> highway
                "grade" -> grade
                "sac_scale" -> sacScale
                else -> access
            }
    }

    private companion object {
        val PROFILES = listOf("walk", "bike", "hike")
    }
}
