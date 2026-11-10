package nl.r1a.grassmap.places

import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The T-0204 acceptance set: kinds and brands found whatever the name says,
 * and street wording accepted in either the short or the full form.
 *
 * The probes are the committed `kind-brand-queries.tsv`, read rather than
 * restated. The test computes found/probes from the run and fails on a miss,
 * so a changed result is visible as a score, never as a hardcoded pass.
 */
class KindBrandSearchTest {
    @Test
    fun `the kind brand and wording query set is fully found`() {
        val probes = readProbes()
        assertTrue("the committed query set is empty", probes.isNotEmpty())
        val misses = mutableListOf<String>()
        for (probe in probes) {
            val hits = search(probe.query, probe.limit)
            val rank = hits.indexOfFirst { matches(it, probe) }
            if (rank < 0) {
                misses += "${probe.query} -> ${probe.expectedName} [${probe.expectedKinds}]"
                println(
                    "miss: ${probe.query} -> ${probe.expectedName} [${probe.expectedKinds}] in " +
                        hits.joinToString { "${it.name} [${it.kind}] brand=${it.brand}" },
                )
            } else {
                println("${probe.query}\t${probe.expectedName}\t#${rank + 1}")
            }
        }
        val found = probes.size - misses.size
        val score = found.toDouble() / probes.size
        println("kind/brand query set: $found/${probes.size} (score $score)")
        assertTrue(
            "kind/brand score $found/${probes.size} is below $REQUIRED_SCORE: " +
                misses.joinToString("; "),
            score >= REQUIRED_SCORE,
        )
    }

    @Test
    fun `a branch is found by its brand tag when its name does not carry it`() {
        val hits = search("укрпошта №35", RESULT_LIMIT)
        val branch = hits.firstOrNull() ?: throw AssertionError("no hit for a brand query")
        assertEquals("Відділення поштового зв’язку №35", branch.name)
        assertEquals("Укрпошта", branch.brand)
        assertTrue(
            "the branch name already carries the brand: ${branch.name}",
            !branch.name.contains("Укрпошта", ignoreCase = true),
        )
    }

    private fun matches(
        hit: PlaceHit,
        probe: Probe,
    ): Boolean {
        val nameMatches = probe.expectedName == ANY_NAME || hit.name.equals(probe.expectedName, ignoreCase = true)
        val kindMatches =
            probe.expectedKinds.isEmpty() ||
                probe.expectedKinds.split('|').any { it == hit.kind }
        val brandMatches =
            probe.expectedBrand.isEmpty() ||
                hit.brand?.equals(probe.expectedBrand, ignoreCase = true) == true
        return nameMatches && kindMatches && brandMatches
    }

    private fun search(
        query: String,
        limit: Int,
    ): List<PlaceHit> {
        val outcome =
            runBlocking {
                provider.search(
                    query = query,
                    context = SearchContext(position = LVIV_CENTRE),
                    limit = limit,
                    cancellation = CancellationHandle.None,
                )
            }
        return (outcome as? Outcome.Success)?.value ?: emptyList()
    }

    private data class Probe(
        val query: String,
        val expectedName: String,
        val expectedKinds: String,
        val expectedBrand: String,
        val limit: Int,
    )

    private fun readProbes(): List<Probe> {
        val text =
            javaClass.getResourceAsStream("/search/kind-brand-queries.tsv")?.bufferedReader()?.use { it.readText() }
                ?: error("missing /search/kind-brand-queries.tsv")
        return text
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val columns = line.split('\t')
                Probe(
                    query = columns.getOrElse(0) { "" },
                    expectedName = columns.getOrElse(1) { "" },
                    expectedKinds = columns.getOrElse(2) { "" },
                    expectedBrand = columns.getOrElse(3) { "" },
                    limit = columns.getOrElse(4) { "" }.toIntOrNull() ?: RESULT_LIMIT,
                )
            }.filter { it.query.isNotEmpty() && it.expectedName.isNotEmpty() }
            .toList()
    }

    companion object {
        private const val RESULT_LIMIT = 5
        private val LVIV_CENTRE = GeoPoint(49.8419, 24.0316)
        private const val ANY_NAME = "*"

        /** Every committed probe must be found; the score is computed from the file. */
        private const val REQUIRED_SCORE = 1.0

        private lateinit var provider: RustPlaceProvider

        @BeforeClass
        @JvmStatic
        fun setUp() {
            provider = TestSearchIndex.openAddresses("lviv-kind-brand-test")
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            provider.close()
        }
    }
}
