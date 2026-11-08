package nl.r1a.grassmap.places

import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Place
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** The `places.search` 1.0.0 edge cases: typed failures, not exceptions. */
class RustPlaceProviderContractTest {
    private fun search(
        query: String,
        limit: Int = 5,
        cancellation: CancellationHandle = CancellationHandle.None,
    ): Outcome<List<PlaceHit>> = runBlocking { provider.search(query, SearchContext(), limit, cancellation) }

    private fun errorOf(outcome: Outcome<*>): AppError =
        (outcome as? Outcome.Failure)?.error
            ?: throw AssertionError("expected a failure, got $outcome")

    @Test
    fun `a cancelled call returns no result`() {
        val outcome = search("львів", cancellation = CancellationHandle { true })
        assertEquals(AppError.Cancelled, errorOf(outcome))
    }

    @Test
    fun `a cancelled poi call returns no result`() {
        val outcome =
            runBlocking {
                provider.pois(LVIV_CENTRE_BOX, emptySet(), 5, CancellationHandle { true })
            }
        assertEquals(AppError.Cancelled, errorOf(outcome))
    }

    @Test
    fun `malformed queries are invalid input`() {
        assertEquals(AppError.InvalidInput, errorOf(search("")))
        assertEquals(AppError.InvalidInput, errorOf(search("   ")))
        assertEquals(AppError.InvalidInput, errorOf(search("львів", limit = 0)))
        assertEquals(AppError.InvalidInput, errorOf(search("львів", limit = MAX_SEARCH_RESULTS + 1)))
    }

    @Test
    fun `details round-trips a hit`() {
        val hits = (search("площа ринок") as Outcome.Success).value
        assertTrue("no hit for площа ринок", hits.isNotEmpty())
        val top = hits.first()
        val details = (runBlocking { provider.details(top.id, CancellationHandle.None) } as Outcome.Success).value
        assertEquals(top.name, details.place.name)
        assertEquals(top.kind, details.place.kind)
        assertEquals(top.position, details.place.position)
        assertEquals("lviv-test", details.place.regionBuild)
        // The committed P4a section predates the features component, so the
        // empty placeholder carries no identity and no tags for any place.
        assertNull(details.osmType)
        assertNull(details.osmId)
        assertTrue(details.tags.isEmpty())
    }

    @Test
    fun `details rejects an unknown id and a foreign region`() {
        assertEquals(
            AppError.NotFound,
            errorOf(runBlocking { provider.details("lviv-test:4294967295", CancellationHandle.None) }),
        )
        assertEquals(
            AppError.NotFound,
            errorOf(runBlocking { provider.details("kyiv-test:0", CancellationHandle.None) }),
        )
    }

    @Test
    fun `pois filters by box and exact category`() {
        val all =
            runBlocking { provider.pois(LVIV_CENTRE_BOX, emptySet(), 10, CancellationHandle.None) }
                as Outcome.Success
        assertTrue("no places in the Lviv centre box", all.value.isNotEmpty())
        assertTrue(all.value.all { inBox(it) })

        val cities =
            runBlocking {
                provider.pois(LVIV_CENTRE_BOX, setOf("place=city"), 10, CancellationHandle.None)
            } as Outcome.Success
        assertTrue(cities.value.isNotEmpty())
        assertTrue(cities.value.all { it.kind == "place=city" && inBox(it) })
    }

    @Test
    fun `an inverted box is invalid input`() {
        val inverted = BoundingBox(northEast = GeoPoint(49.80, 24.00), southWest = GeoPoint(49.90, 24.10))
        val outcome = runBlocking { provider.pois(inverted, emptySet(), 5, CancellationHandle.None) }
        assertEquals(AppError.InvalidInput, errorOf(outcome))
    }

    @Test
    fun `a missing index directory is not ready`() {
        val missing = File(TestSearchIndex.directory.toFile().parentFile, "no-such-index")
        val opened = runBlocking { RustPlaceProvider.open(missing, "missing") }
        assertEquals(AppError.NotReady, errorOf(opened))
    }

    private fun inBox(place: Place): Boolean =
        place.position.latitude in LVIV_CENTRE_BOX.southWest.latitude..LVIV_CENTRE_BOX.northEast.latitude &&
            place.position.longitude in
            LVIV_CENTRE_BOX.southWest.longitude..LVIV_CENTRE_BOX.northEast.longitude

    companion object {
        private val LVIV_CENTRE_BOX =
            BoundingBox(
                southWest = GeoPoint(49.80, 24.00),
                northEast = GeoPoint(49.88, 24.08),
            )

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
