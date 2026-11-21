package nl.r1a.grassmaps.places

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmaps.plugin.api.contract.CancellationHandle
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.BoundingBox
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.provider.PlaceHit
import nl.r1a.grassmaps.plugin.api.provider.SearchContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Multi-pack search (T-0633): every mounted section answers, the rows merge,
 * a place in two packs is one row, ranking and the cap are deterministic, and
 * a newer keystroke cancels the whole older query.
 */
class MultiRegionSearchTest {
    @Test
    fun aHitInEitherMountedPackIsFound() =
        runTest {
            val first = hit("$FIRST:1", "Ринок", 49.8413, 24.0312, FIRST)
            val second = hit("$SECOND:1", "Золочів", 49.8056, 24.8989, SECOND)
            val search = search(mapOf(FIRST to listOf(first), SECOND to listOf(second)))

            val result = search.query(sections(), "place").valueOrFail()

            assertEquals(setOf(first, second), result.toSet())
        }

    @Test
    fun theSamePlaceInTwoPacksIsOneRowAndKeepsTheSectionThatOpensIt() =
        runTest {
            val lower = hit("$FIRST:7", "Площа Ринок", 49.84130, 24.03120, FIRST, score = 0.4f)
            val higher = hit("$SECOND:9", "площа ринок!", 49.84131, 24.03121, SECOND, score = 0.9f)
            val search = search(mapOf(FIRST to listOf(lower), SECOND to listOf(higher)))

            val result = search.query(sections(), "ринок").valueOrFail()

            assertEquals(1, result.size)
            assertEquals("the better-ranked copy keeps its own region and id", higher, result.single())
            assertEquals(SECOND, result.single().regionBuild)
        }

    @Test
    fun theSamePlaceIdInTwoPacksIsOneRow() =
        runTest {
            val first = hit("$FIRST:4", "Ринок", 49.8413, 24.0312, FIRST, score = 0.9f)
            val second = hit("$FIRST:4", "Інша назва", 49.9000, 24.1000, SECOND, score = 0.1f)
            val search = search(mapOf(FIRST to listOf(first), SECOND to listOf(second)))

            val result = search.query(sections(), "ринок").valueOrFail()

            assertEquals(listOf(first), result)
        }

    @Test
    fun theProviderScoreOutranksDistance() =
        runTest {
            val far = hit("$FIRST:1", "Далекий", 49.9000, 24.1000, FIRST, score = 0.9f)
            val near = hit("$SECOND:1", "Близький", 49.8410, 24.0310, SECOND, score = 0.8f)
            val search = search(mapOf(FIRST to listOf(far), SECOND to listOf(near)))

            val result =
                search
                    .query(sections(), "place", context = SearchContext(position = ORIGIN))
                    .valueOrFail()

            assertEquals(listOf(far.id, near.id), result.map { it.id })
        }

    @Test
    fun anEqualScoreRanksByDistanceFromTheCurrentFix() =
        runTest {
            val far = hit("$FIRST:1", "Далекий", 49.9000, 24.1000, FIRST, score = 0.5f)
            val near = hit("$SECOND:1", "Близький", 49.8410, 24.0310, SECOND, score = 0.5f)
            val search = search(mapOf(FIRST to listOf(far), SECOND to listOf(near)))

            val result =
                search
                    .query(sections(), "place", context = SearchContext(position = ORIGIN))
                    .valueOrFail()

            assertEquals(listOf(near.id, far.id), result.map { it.id })
        }

    @Test
    fun theViewportCenterRanksWhenThereIsNoFix() =
        runTest {
            val middle = hit("$FIRST:1", "Центр", 49.8501, 24.0501, FIRST, score = 0.5f)
            val edge = hit("$SECOND:1", "Край", 49.8990, 24.0990, SECOND, score = 0.5f)
            val search = search(mapOf(FIRST to listOf(middle), SECOND to listOf(edge)))
            val viewport =
                BoundingBox(
                    southWest = GeoPoint(49.8000, 24.0000),
                    northEast = GeoPoint(49.9000, 24.1000),
                )

            val result =
                search
                    .query(sections(), "place", context = SearchContext(viewport = viewport))
                    .valueOrFail()

            assertEquals(listOf(middle.id, edge.id), result.map { it.id })
        }

    @Test
    fun theScreenLimitCapsTheMergedRows() =
        runTest {
            val first =
                listOf(
                    hit("$FIRST:1", "А", 49.8401, 24.0311, FIRST, score = 0.9f),
                    hit("$FIRST:2", "Б", 49.8402, 24.0312, FIRST, score = 0.7f),
                    hit("$FIRST:3", "В", 49.8403, 24.0313, FIRST, score = 0.5f),
                )
            val second =
                listOf(
                    hit("$SECOND:1", "Г", 49.8404, 24.0314, SECOND, score = 0.8f),
                    hit("$SECOND:2", "Ґ", 49.8405, 24.0315, SECOND, score = 0.6f),
                    hit("$SECOND:3", "Д", 49.8406, 24.0316, SECOND, score = 0.4f),
                )
            val search = search(mapOf(FIRST to first, SECOND to second))

            val result = search.query(sections(), "place", limit = 3).valueOrFail()

            assertEquals(
                listOf("$FIRST:1", "$SECOND:1", "$FIRST:2"),
                result.map { it.id },
            )
        }

    @Test
    fun aNewerQueryCancelsTheWholeOlderSearch() =
        runTest {
            val olderStarted = CompletableDeferred<Unit>()
            val releaseOlder = CompletableDeferred<Unit>()
            val search =
                MultiRegionSearch { section, query, _, _, _ ->
                    if (query == "old") {
                        olderStarted.complete(Unit)
                        releaseOlder.await()
                        Outcome.Success(listOf(hit("$section:old", "Старий $section", 49.8410, 24.0310, section)))
                    } else {
                        Outcome.Success(listOf(hit("$section:new", "Новий $section", 49.8410, 24.0310, section)))
                    }
                }
            val session =
                SearchScreenSession { request ->
                    search.query(
                        sections(),
                        request.query,
                        request.context,
                        request.limit,
                        request.cancellation,
                    )
                }

            val older = launch { session.submit("old", sequence = 1) }
            olderStarted.await()
            val newer = launch { session.submit("new", sequence = 2) }
            newer.join()
            releaseOlder.complete(Unit)
            older.join()

            assertEquals(
                listOf("$FIRST:new", "$SECOND:new"),
                session.state.value.hits
                    .map { it.id }
                    .sorted(),
            )
            assertEquals(SearchScreenStatus.RESULTS, session.state.value.status)
        }

    @Test
    fun aSectionNamedTwiceIsQueriedOnce() =
        runTest {
            val calls = mutableListOf<String>()
            val search =
                MultiRegionSearch { section, _, _, _, _ ->
                    calls += section
                    Outcome.Success(emptyList())
                }

            search.query(listOf(FIRST, FIRST, SECOND), "place").valueOrFail()

            assertEquals(listOf(FIRST, SECOND).sorted(), calls.sorted())
        }

    @Test
    fun aFailedSectionDoesNotHideTheOthers() =
        runTest {
            val second = hit("$SECOND:1", "Золочів", 49.8056, 24.8989, SECOND)
            val search =
                MultiRegionSearch { section, _, _, _, _ ->
                    if (section == FIRST) {
                        Outcome.Failure(AppError.CorruptData)
                    } else {
                        Outcome.Success(listOf(second))
                    }
                }

            val result = search.query(sections(), "place").valueOrFail()

            assertEquals(listOf(second), result)
        }

    @Test
    fun everyFailedSectionReturnsTheFirstFailure() =
        runTest {
            val search =
                MultiRegionSearch { section, _, _, _, _ ->
                    if (section == FIRST) {
                        Outcome.Failure(AppError.CorruptData)
                    } else {
                        Outcome.Failure(AppError.NotReady)
                    }
                }

            assertEquals(
                Outcome.Failure(AppError.CorruptData),
                search.query(sections(), "place"),
            )
        }

    @Test
    fun noMountedSectionIsNotReady() =
        runTest {
            val search = search(emptyMap())

            assertEquals(
                Outcome.Failure(AppError.NotReady),
                search.query(emptyList(), "place"),
            )
        }

    private fun search(
        hitsBySection: Map<String, List<PlaceHit>>,
        onSection: (String) -> Unit = {},
    ): MultiRegionSearch =
        MultiRegionSearch { section, _, _, _, _ ->
            onSection(section)
            Outcome.Success(hitsBySection[section].orEmpty())
        }

    private suspend fun MultiRegionSearch.query(
        sections: List<String>,
        query: String,
        context: SearchContext = SearchContext(),
        limit: Int = 5,
        cancellation: CancellationHandle = CancellationHandle.None,
    ): Outcome<List<PlaceHit>> = this.search(sections, query, context, limit, cancellation)

    private fun hit(
        id: String,
        name: String,
        latitude: Double,
        longitude: Double,
        region: String,
        score: Float = 1f,
    ): PlaceHit =
        PlaceHit(
            id = id,
            name = name,
            kind = "place=square",
            position = GeoPoint(latitude, longitude),
            address = null,
            regionBuild = region,
            score = score,
        )

    private fun <T> Outcome<T>.valueOrFail(): T =
        when (this) {
            is Outcome.Success -> value
            is Outcome.Failure -> throw AssertionError("expected success, got $error")
        }

    private fun sections(): List<String> = listOf(FIRST, SECOND)

    private companion object {
        const val FIRST = "ua-46"
        const val SECOND = "ua-65"
        val ORIGIN = GeoPoint(49.8419, 24.0316)
    }
}
