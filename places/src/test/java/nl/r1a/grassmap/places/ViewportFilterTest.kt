package nl.r1a.grassmap.places

import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * "Search this area" over the real Lviv section (T-0206).
 *
 * The provider receives the viewport in [SearchContext.viewport] and every hit
 * it returns must lie inside it, edges included. The tests are built so that
 * ignoring the viewport cannot pass: the anchor box is smaller than the spread
 * of the unfiltered result set, so at least one unfiltered hit is outside it,
 * and a box at the exact point of a hit proves the inclusive edge.
 */
class ViewportFilterTest {
    @Test
    fun `every hit is inside the viewport that was asked for`() =
        runBlocking {
            val all = search(QUERY, MAX_SEARCH_RESULTS)
            val spread = all.map { it.position }.distinct()
            assertTrue("the probe query must return several distinct points, got $spread", spread.size > 1)
            val anchor = spread.first()
            val box =
                BoundingBox(
                    southWest = GeoPoint(anchor.latitude - BOX_DEGREES, anchor.longitude - BOX_DEGREES),
                    northEast = GeoPoint(anchor.latitude + BOX_DEGREES, anchor.longitude + BOX_DEGREES),
                )
            val outside = all.filterNot { box.contains(it.position) }
            assertTrue("the box must exclude at least one unfiltered hit, or the test proves nothing", outside.isNotEmpty())

            val inside = search(QUERY, MAX_SEARCH_RESULTS, box)

            assertTrue("the viewport returned nothing at all", inside.isNotEmpty())
            assertTrue(
                "result outside the viewport: ${inside.filterNot { box.contains(it.position) }}",
                inside.all { box.contains(it.position) },
            )
        }

    @Test
    fun `a hit exactly on the viewport edge is inside`() =
        runBlocking {
            val top = search(QUERY, RESULT_LIMIT).first()
            val edge = BoundingBox(southWest = top.position, northEast = top.position)

            val hits = search(QUERY, MAX_SEARCH_RESULTS, edge)

            assertTrue("a degenerate box on a hit must keep that hit: $hits", hits.any { it.id == top.id })
        }

    @Test
    fun `a viewport away from the city returns nothing`() =
        runBlocking {
            val elsewhere =
                BoundingBox(
                    southWest = GeoPoint(-1.0, -1.0),
                    northEast = GeoPoint(1.0, 1.0),
                )

            assertEquals(emptyList<PlaceHit>(), search(QUERY, RESULT_LIMIT, elsewhere))
        }

    private suspend fun search(
        query: String,
        limit: Int,
        viewport: BoundingBox? = null,
    ): List<PlaceHit> {
        val outcome =
            provider.search(
                query = query,
                context = SearchContext(viewport = viewport, position = LVIV_CENTRE),
                limit = limit,
                cancellation = CancellationHandle.None,
            )
        return (outcome as? Outcome.Success)?.value ?: emptyList()
    }

    companion object {
        private const val QUERY = "шевченка"
        private const val RESULT_LIMIT = 10
        private const val BOX_DEGREES = 0.002
        private val LVIV_CENTRE = GeoPoint(49.8419, 24.0316)

        private lateinit var provider: RustPlaceProvider

        @BeforeClass
        @JvmStatic
        fun setUp() {
            provider = TestSearchIndex.open("lviv-viewport-test")
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            provider.close()
        }
    }
}
