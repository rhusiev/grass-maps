package nl.r1a.grassmap.app

import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.places.COORDINATE_HIT_ID
import nl.r1a.grassmap.places.PlacesAccess
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import nl.r1a.grassmap.plugins.coordinates.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app wires the real parser in front of the search host (T-0206).
 *
 * No region index exists here: the root is null, so an ordinary query is
 * `NotReady`. A coordinate still produces the one row the user can act on,
 * which is the whole point of reading the text before the index. The notations
 * themselves are the parser's committed vectors; this test names the two the
 * acceptance criterion calls out, decimal and DMS.
 */
class CoordinateSearchWiringTest {
    private val access = PlacesAccess(externalFilesRoot = null, coordinateParser = Coordinates.parser)

    @Test
    fun `a decimal coordinate is a row without an index`() =
        runBlocking {
            val hits = search("49.841740, 24.023910")
            val hit = hits?.singleOrNull()

            assertEquals("expected one coordinate row, got $hits", 1, hits?.size)
            assertEquals(COORDINATE_HIT_ID, hit?.id)
            assertEquals("49.841740, 24.023910", hit?.name)
            assertEquals(49.841740, hit?.position?.latitude ?: Double.NaN, ROUND_TRIP_DEGREES)
            assertEquals(24.023910, hit?.position?.longitude ?: Double.NaN, ROUND_TRIP_DEGREES)
            assertTrue("the row is a point, not a place", hit?.isCoordinate == true)
        }

    @Test
    fun `a DMS coordinate is a row without an index`() =
        runBlocking {
            val hit = search("49°49'18.26\"N, 24°01'26.08\"E")?.singleOrNull()

            assertEquals(COORDINATE_HIT_ID, hit?.id)
            assertEquals(49.821738888889, hit?.position?.latitude ?: Double.NaN, ROUND_TRIP_DEGREES)
            assertEquals(24.023911111111, hit?.position?.longitude ?: Double.NaN, ROUND_TRIP_DEGREES)
        }

    @Test
    fun `an ordinary query still needs the index`() =
        runBlocking {
            val outcome =
                access.search(
                    indexName = "lviv",
                    query = "львів",
                    context = SearchContext(),
                    limit = LIMIT,
                    cancellation = CancellationHandle.None,
                )

            assertEquals(Outcome.Failure(AppError.NotReady), outcome)
        }

    @Test
    fun `this area keeps the coordinate and drops it outside the viewport`() =
        runBlocking {
            val around =
                BoundingBox(
                    southWest = GeoPoint(49.80, 24.00),
                    northEast = GeoPoint(49.90, 24.10),
                )
            val elsewhere =
                BoundingBox(
                    southWest = GeoPoint(50.80, 25.00),
                    northEast = GeoPoint(50.90, 25.10),
                )

            assertEquals(1, search("49.841740, 24.023910", around)?.size)
            assertEquals(0, search("49.841740, 24.023910", elsewhere)?.size)
        }

    private suspend fun search(
        query: String,
        viewport: BoundingBox? = null,
    ): List<PlaceHit>? =
        when (
            val outcome =
                access.search(
                    indexName = "lviv",
                    query = query,
                    context = SearchContext(viewport = viewport),
                    limit = LIMIT,
                    cancellation = CancellationHandle.None,
                )
        ) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> null
        }

    private companion object {
        const val LIMIT = 5

        /** The formatter and the parser agree well inside a metre. */
        const val ROUND_TRIP_DEGREES = 1e-9
    }
}
