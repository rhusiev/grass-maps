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
 * The house-number acceptance set: 50 fixed Lviv addresses, each queried the
 * way a person types it, top one must be the advertised house.
 *
 * The expected value is the coordinate the extractor wrote for that row, so a
 * rebuild from the same OSM snapshot reproduces it and a different snapshot
 * shows up as a miss instead of silently passing on a stale id.
 */
class LvivAddressSearchTest {
    @Test
    fun `at least 45 of 50 fixed addresses come top one`() {
        val probes = readProbes()
        assertEquals("the query set is fixed at 50", PROBE_COUNT, probes.size)
        var topOne = 0
        val misses = mutableListOf<String>()
        for (probe in probes) {
            val first = search(probe.query).firstOrNull()
            if (first != null && distanceM(first.position, GeoPoint(probe.latitude, probe.longitude)) <= MAX_METRES) {
                topOne += 1
            } else {
                misses += "${probe.query} -> ${first?.let { "${it.name} at ${it.position}" } ?: "no hit"}"
            }
        }
        println("Lviv addresses: top-1 $topOne/$PROBE_COUNT")
        misses.forEach { println("miss: $it") }
        assertTrue(
            "top-1 $topOne/$PROBE_COUNT, need $MIN_TOP_ONE: ${misses.joinToString("; ")}",
            topOne >= MIN_TOP_ONE,
        )
    }

    @Test
    fun `a bare house number does not flood the results`() {
        for (number in listOf("45", "12", "1")) {
            val hits = search(number, limit = MAX_SEARCH_RESULTS)
            assertTrue(
                "query '$number' returned house rows: ${hits.take(5).map { it.name }}",
                hits.none { it.kind == ADDRESS_KIND },
            )
        }
    }

    @Test
    fun `a house is found with and without the street type word`() {
        val probe = requireNotNull(readProbes().firstOrNull())
        val words = probe.query.split(' ')
        val withTypeWord = "вулиця " + words.joinToString(" ")
        for (query in listOf(probe.query, withTypeWord)) {
            val first = search(query).firstOrNull()
            assertTrue(
                "'$query' found ${first?.name}",
                first != null &&
                    distanceM(first.position, GeoPoint(probe.latitude, probe.longitude)) <= MAX_METRES,
            )
        }
    }

    private fun search(
        query: String,
        limit: Int = RESULT_LIMIT,
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

    private fun distanceM(
        a: GeoPoint,
        b: GeoPoint,
    ): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(b.longitude - a.longitude)
        val h =
            Math.sin(deltaLat / 2.0) * Math.sin(deltaLat / 2.0) +
                Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2.0) * Math.sin(deltaLon / 2.0)
        return 2.0 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h))
    }

    private data class Probe(
        val query: String,
        val latitude: Double,
        val longitude: Double,
    )

    private fun readProbes(): List<Probe> {
        val text =
            javaClass.getResourceAsStream("/search/addresses.tsv")?.bufferedReader()?.use { it.readText() }
                ?: error("missing /search/addresses.tsv")
        return text
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val columns = line.split('\t')
                Probe(
                    query = columns.getOrElse(0) { "" },
                    latitude = columns.getOrElse(1) { "" }.toDouble(),
                    longitude = columns.getOrElse(2) { "" }.toDouble(),
                )
            }.filter { it.query.isNotEmpty() }
            .toList()
    }

    companion object {
        private const val PROBE_COUNT = 50
        private const val MIN_TOP_ONE = 45
        private const val RESULT_LIMIT = 5
        private const val MAX_METRES = 100.0
        private const val EARTH_RADIUS_M = 6_371_000.0
        private const val ADDRESS_KIND = "addr=house"
        private val LVIV_CENTRE = GeoPoint(49.8419, 24.0316)

        private lateinit var provider: RustPlaceProvider

        @BeforeClass
        @JvmStatic
        fun setUp() {
            provider = TestSearchIndex.openAddresses()
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            provider.close()
        }
    }
}
