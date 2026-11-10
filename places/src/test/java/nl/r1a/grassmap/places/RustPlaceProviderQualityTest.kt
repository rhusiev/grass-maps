package nl.r1a.grassmap.places

import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The P4a probe set is the quality oracle: the same queries, the same expected
 * name and kind, the same five-result limit and Lviv centre origin.
 *
 * The score is computed from the committed `queries.tsv`: a fixed table is what
 * the probes are, and the test reads it rather than restating a number. T-0203
 * requires every probe in the top five, so the required score is 1.0.
 */
class RustPlaceProviderQualityTest {
    @Test
    fun `every probe finds its expected place in the top five`() {
        val probes = readProbes()
        assertTrue("the committed probe set is empty", probes.isNotEmpty())
        var misses = 0
        for (probe in probes) {
            val outcome =
                runBlocking {
                    provider.search(
                        query = probe.query,
                        context = SearchContext(position = LVIV_CENTRE),
                        limit = RESULT_LIMIT,
                        cancellation = CancellationHandle.None,
                    )
                }
            val hits = (outcome as? Outcome.Success)?.value ?: emptyList()
            val rank = hits.indexOfFirst { matches(it, probe) }
            if (rank < 0) {
                misses += 1
                println("miss: ${probe.query} -> ${probe.expectedName} [${probe.expectedKind}] in $hits")
            } else {
                println("${probe.query}\t${probe.expectedName}\t#${rank + 1}")
            }
        }
        val found = probes.size - misses
        println("osm-mapidx quality: $found/${probes.size} (score ${found.toDouble() / probes.size})")
        assertTrue(
            "osm-mapidx score $found/${probes.size} is below the required $REQUIRED_SCORE",
            found.toDouble() / probes.size >= REQUIRED_SCORE,
        )
    }

    @Test
    fun `a full replay reports per-keystroke latency`() {
        val queries = readProbes().map { it.query }
        val timings = mutableListOf<Long>()
        runBlocking {
            for (query in queries) {
                val chars = query.toCharArray()
                for (length in 1..chars.size) {
                    val started = System.nanoTime()
                    provider.search(
                        String(chars, 0, length),
                        SearchContext(position = LVIV_CENTRE),
                        RESULT_LIMIT,
                        CancellationHandle.None,
                    )
                    timings += TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started)
                }
            }
        }
        timings.sort()
        val p50 = timings[timings.size / 2]
        val p99 = timings[(timings.size * 99) / 100]
        println("JVM replay: ${timings.size} keystrokes, p50 ${p50}us, p99 ${p99}us, max ${timings.last()}us")
        assertTrue("the replay produced no keystrokes", timings.isNotEmpty())
    }

    private fun matches(
        hit: PlaceHit,
        probe: Probe,
    ): Boolean {
        val nameMatches = probe.expectedName == ANY_NAME || hit.name.equals(probe.expectedName, ignoreCase = true)
        val kindMatches =
            probe.expectedKind.isEmpty() ||
                probe.expectedKind.split('|').any { it == hit.kind }
        return nameMatches && kindMatches
    }

    private data class Probe(
        val query: String,
        val expectedName: String,
        val expectedKind: String,
    )

    private fun readProbes(): List<Probe> {
        val text =
            javaClass.getResourceAsStream("/search/queries.tsv")?.bufferedReader()?.use { it.readText() }
                ?: error("missing /search/queries.tsv")
        return text
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val columns = line.split('\t')
                Probe(
                    query = columns.getOrElse(0) { "" },
                    expectedName = columns.getOrElse(1) { "" },
                    expectedKind = columns.getOrElse(2) { "" },
                )
            }.filter { it.expectedName.isNotEmpty() }
            .toList()
    }

    companion object {
        private val RESULT_LIMIT = 5
        private val LVIV_CENTRE = GeoPoint(49.8419, 24.0316)
        private const val ANY_NAME = "*"

        /** Every committed probe must be found; the score is computed from the file. */
        private const val REQUIRED_SCORE = 1.0

        private lateinit var provider: RustPlaceProvider

        @BeforeClass
        @JvmStatic
        fun setUp() {
            provider = TestSearchIndex.open()
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            provider.close()
        }
    }
}
