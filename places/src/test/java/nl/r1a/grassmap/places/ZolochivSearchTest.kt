package nl.r1a.grassmap.places

import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The T-0205 acceptance case: one town, three spellings, one top result.
 *
 * The committed section under `/search/zolochiv` is built by the real pipeline
 * (`tools/pipeline/osm-extract`, then `build-section`) from the 2026-11-04
 * OSM extract, so this test exercises what ships and not a hand-written index.
 * "Złoczów" is the town's `name:pl` in OSM, which the extractor now carries into
 * the row's alias column; "Zolochiv" and "zolochivka" come from the
 * transliteration table applied by the builder. Both mechanisms are covered
 * here because a reader types both.
 */
class ZolochivSearchTest {
    @Test
    fun `the three spellings of one town give the same top result`() {
        val tops = SPELLINGS.map { spelling -> spelling to search(spelling).firstOrNull() }
        for ((spelling, top) in tops) {
            assertNotNull("no result for $spelling", top)
            assertEquals("top result for $spelling", TOWN_NAME, top?.name)
            assertEquals("kind for $spelling", TOWN_KIND, top?.kind)
        }
        val ids = tops.mapNotNull { it.second?.id }.toSet()
        assertEquals("the three spellings name different rows: $tops", 1, ids.size)
    }

    @Test
    fun `a spelling with no diacritics reaches a name that has them`() {
        // "Złoczów" folds to "zloczow": ł is not a combining mark, so the fold
        // maps it by hand. If it did not, the town's own name:pl would be a
        // term no Polish keyboard produces.
        assertEquals(TOWN_NAME, search("zloczow").firstOrNull()?.name)
        assertEquals(TOWN_NAME, search("ZŁOCZÓW").firstOrNull()?.name)
    }

    @Test
    fun `a Cyrillic village is found by its transliterated Latin spelling`() {
        // The village carries only `name=Золочівка` in OSM. The Latin spelling
        // exists because the builder romanised the name, not because OSM has
        // it, which is the half of the feature the Polish exonym does not cover.
        for (spelling in listOf("zolochivka", "zolocivka")) {
            assertEquals("top result for $spelling", "Золочівка", search(spelling).firstOrNull()?.name)
        }
    }

    @Test
    fun `the named case is not a fold collision`() {
        // One town in the section answers to "zloczow": the alias is unique, so
        // the top result is not one of two places the fold made the same.
        val towns = search("золочів").filter { it.kind == TOWN_KIND }
        assertEquals("the section holds one town named $TOWN_NAME", 1, towns.size)
        assertEquals(TOWN_NAME, towns.single().name)
    }

    private fun search(
        query: String,
        limit: Int = RESULT_LIMIT,
    ): List<PlaceHit> {
        val outcome =
            runBlocking {
                provider.search(
                    query = query,
                    context = SearchContext(position = ORIGIN),
                    limit = limit,
                    cancellation = CancellationHandle.None,
                )
            }
        return (outcome as? Outcome.Success)?.value ?: emptyList()
    }

    companion object {
        private const val TOWN_NAME = "Золочів"
        private const val TOWN_KIND = "place=town"
        private const val RESULT_LIMIT = 5
        private val SPELLINGS = listOf("zloczow", "Złoczów", "Золочів")

        /** The town's own coordinates, the origin a person there would have. */
        private val ORIGIN = GeoPoint(49.8056, 24.8989)

        private lateinit var provider: RustPlaceProvider

        @BeforeClass
        @JvmStatic
        fun setUp() {
            provider = TestSearchIndex.openZolochiv()
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            provider.close()
        }
    }
}
