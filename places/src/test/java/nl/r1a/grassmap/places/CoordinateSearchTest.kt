package nl.r1a.grassmap.places

import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.CoordinateParser
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coordinate search in the provider host (T-0206).
 *
 * The parser is a stub here: the notations themselves are pinned by
 * `plugins/coordinates`' committed vectors, and the app test runs this same
 * host with the real parser. What this class proves is the seam: a parsed
 * coordinate is one row at that point with no index at all, the row says it is
 * a point rather than a place, and "this area" applies to it.
 */
class CoordinateSearchTest {
    private val point = GeoPoint(49.8419, 24.0316)
    private val parser = CoordinateParser { text -> if (text == TYPED) point else null }

    @Test
    fun `a typed coordinate is one row without a region index`() =
        runBlocking {
            val access = PlacesAccess(externalFilesRoot = null, coordinateParser = parser)
            val outcome = access.search("lviv", TYPED, SearchContext(), LIMIT, CancellationHandle.None)

            val hit = (outcome as? Outcome.Success)?.value?.singleOrNull()
            assertTrue("expected one coordinate row, got $outcome", hit != null)
            assertEquals(COORDINATE_HIT_ID, hit?.id)
            assertEquals(point, hit?.position)
            assertEquals(TYPED, hit?.name)
            assertEquals("", hit?.kind)
            assertNull(hit?.address)
            assertNull(hit?.brand)
            assertTrue("the row is a point, not a place", hit?.isCoordinate == true)
        }

    @Test
    fun `without a parser the same text needs an index`() =
        runBlocking {
            val access = PlacesAccess(externalFilesRoot = null)
            val outcome = access.search("lviv", TYPED, SearchContext(), LIMIT, CancellationHandle.None)

            assertEquals(Outcome.Failure(AppError.NotReady), outcome)
        }

    @Test
    fun `this area hides a coordinate outside the viewport`() =
        runBlocking {
            val access = PlacesAccess(externalFilesRoot = null, coordinateParser = parser)
            val away =
                BoundingBox(
                    southWest = GeoPoint(0.0, 0.0),
                    northEast = GeoPoint(1.0, 1.0),
                )
            val outcome = access.search("lviv", TYPED, SearchContext(viewport = away), LIMIT, CancellationHandle.None)

            assertEquals(emptyList<Any>(), (outcome as? Outcome.Success)?.value)
        }

    @Test
    fun `a coordinate on the viewport edge is inside`() =
        runBlocking {
            val access = PlacesAccess(externalFilesRoot = null, coordinateParser = parser)
            val edge = BoundingBox(southWest = point, northEast = point)
            val outcome = access.search("lviv", TYPED, SearchContext(viewport = edge), LIMIT, CancellationHandle.None)

            val hits = (outcome as? Outcome.Success)?.value
            assertEquals(1, hits?.size)
            assertEquals(point, hits?.single()?.position)
        }

    @Test
    fun `an invalid viewport is invalid input`() =
        runBlocking {
            val access = PlacesAccess(externalFilesRoot = null, coordinateParser = parser)
            val inverted =
                BoundingBox(
                    southWest = GeoPoint(50.0, 24.0),
                    northEast = GeoPoint(49.0, 25.0),
                )
            val outcome = access.search("lviv", TYPED, SearchContext(viewport = inverted), LIMIT, CancellationHandle.None)

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
        }

    @Test
    fun `a cancelled keystroke returns no coordinate row`() =
        runBlocking {
            val access = PlacesAccess(externalFilesRoot = null, coordinateParser = parser)
            val outcome = access.search("lviv", TYPED, SearchContext(), LIMIT, CancellationHandle { true })

            assertEquals(Outcome.Failure(AppError.Cancelled), outcome)
        }

    private companion object {
        const val TYPED = "the point"
        const val LIMIT = 5
    }
}
