package nl.r1a.grassmap.places

import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.CoordinateParser
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * The multi-pack query against two real reader sections (T-0633): the same
 * place in both packs becomes one row, and the region that row keeps resolves
 * the place card's details.
 */
class PlacesAccessMultiRegionTest {
    @Test
    fun aPlaceInTwoPacksIsOneRowWhoseSectionOpensItsCard() =
        runBlocking {
            val first = copyFixtureSection()
            val second = copyFixtureSection()
            try {
                val access =
                    PlacesAccess(
                        externalFilesRoot = null,
                        packIndex = { name ->
                            when (name) {
                                FIRST -> first.toFile()
                                SECOND -> second.toFile()
                                else -> null
                            }
                        },
                    )
                val search =
                    MultiRegionSearch { section, query, context, limit, cancellation ->
                        access.search(section, query, context, limit, cancellation)
                    }

                val rows =
                    search
                        .search(
                            sections = listOf(FIRST, SECOND),
                            query = FIXTURE_CAFE_QUERY,
                            context = SearchContext(),
                            limit = 5,
                            cancellation = CancellationHandle.None,
                        ).valueOrFail()

                assertEquals("one place, one row", 1, rows.size)
                val row = rows.single()
                assertTrue(
                    "the row must keep the section it came from",
                    row.regionBuild == FIRST || row.regionBuild == SECOND,
                )
                val details = access.details(row.regionBuild, row.id, CancellationHandle.None)
                val opened = details.valueOrFail()
                assertEquals(FIXTURE_CAFE_NAME, opened.place.name)
                assertEquals(row.position, opened.place.position)
            } finally {
                delete(first)
                delete(second)
            }
        }

    @Test
    fun aTypedCoordinateStillAnswersWithoutAMountedSection() =
        runBlocking {
            val point = GeoPoint(49.8419, 24.0316)
            val access =
                PlacesAccess(
                    externalFilesRoot = null,
                    coordinateParser = CoordinateParser { point },
                )
            val search =
                MultiRegionSearch { section, query, context, limit, cancellation ->
                    access.search(section, query, context, limit, cancellation)
                }

            val rows =
                search
                    .search(
                        sections = listOf(""),
                        query = TYPED_COORDINATE,
                        context = SearchContext(),
                        limit = 5,
                        cancellation = CancellationHandle.None,
                    ).valueOrFail()

            assertEquals(1, rows.size)
            assertEquals(COORDINATE_HIT_ID, rows.single().id)
            assertEquals(point, rows.single().position)
        }

    /** A writable copy per region, so each pack opens its own reader section. */
    private fun copyFixtureSection(): Path {
        val target = Files.createTempDirectory("grass-search-copy")
        Files.list(FIXTURE_SECTION).use { files ->
            files.forEach { file -> Files.copy(file, target.resolve(file.fileName)) }
        }
        return target
    }

    private fun delete(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
        }
    }

    private fun <T> Outcome<T>.valueOrFail(): T =
        when (this) {
            is Outcome.Success -> value
            is Outcome.Failure -> throw AssertionError("expected success, got $error")
        }

    private companion object {
        const val FIRST = "ua-46"
        const val SECOND = "ua-65"
        const val FIXTURE_CAFE_QUERY = "левада"
        const val FIXTURE_CAFE_NAME = "Кав'ярня Левада"
        const val TYPED_COORDINATE = "49.8419, 24.0316"
        val FIXTURE_SECTION: Path = repositoryRoot().resolve("test-data/regions/fixture-mini/2026-11-03-1")
    }
}
