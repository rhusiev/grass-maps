package nl.r1a.grassmap.places

import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.OsmType
import nl.r1a.grassmap.plugin.api.provider.PlaceDetails
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator

/**
 * The committed fixture section, built by `tools/pipeline/build-test-region.sh`
 * from `tools/pipeline/fixture-places.tsv`. It exercises the real builder, not
 * hand-written bytes.
 */
class RustPlaceProviderFixtureTest {
    private fun details(placeId: String): Outcome<PlaceDetails> =
        runBlocking { provider.details("$REGION_BUILD:$placeId", CancellationHandle.None) }

    private fun errorOf(outcome: Outcome<*>): AppError =
        (outcome as? Outcome.Failure)?.error
            ?: throw AssertionError("expected a failure, got $outcome")

    @Test
    fun `the cafe carries its canonical identity and exact tag map`() {
        val details = (details("0") as Outcome.Success).value

        assertEquals(OsmType.NODE, details.osmType)
        assertEquals(CAFE_OSM_ID, details.osmId)
        assertEquals(
            mapOf(
                "amenity" to "cafe",
                "name" to "Кав'ярня Левада",
                "opening_hours" to "Mo-Su 08:00-22:00",
                "phone" to "+380322970545",
                "website" to "https://levada.example",
                "wheelchair" to "yes",
            ),
            details.tags,
        )
        assertEquals("Кав'ярня Левада", details.place.name)
    }

    @Test
    fun `a place with no tags returns an empty map, not null and not an error`() {
        val details = (details("2") as Outcome.Success).value

        assertEquals(OsmType.NODE, details.osmType)
        assertEquals(BENCH_OSM_ID, details.osmId)
        assertEquals(emptyMap<String, String>(), details.tags)
        assertEquals("Лавочка", details.place.name)
    }

    @Test
    fun `the search hit and the details share one place row`() {
        val hits =
            (
                runBlocking {
                    provider.search("левада", SearchContext(), 5, CancellationHandle.None)
                } as Outcome.Success
            ).value
        assertEquals(1, hits.size)
        val hit: PlaceHit = hits.single()
        assertEquals("Кав'ярня Левада", hit.name)

        val details = (details("0") as Outcome.Success).value
        assertEquals(hit.name, details.place.name)
        assertEquals(hit.position, details.place.position)
        assertEquals("yes", details.tags.getValue("wheelchair"))
    }

    @Test
    fun `a non-ASCII brand is searchable and carries its brand tag`() {
        val hits =
            (
                runBlocking {
                    provider.search("атб", SearchContext(), 5, CancellationHandle.None)
                } as Outcome.Success
            ).value
        val shop =
            hits.firstOrNull { it.name == "АТБ" }
                ?: throw AssertionError("no shop hit for атб in $hits")

        val details = (details(shop.id.substringAfterLast(':')) as Outcome.Success).value
        assertEquals("АТБ", details.tags.getValue("brand"))
        assertEquals("supermarket", details.tags.getValue("shop"))
    }

    @Test
    fun `a missing features component is not ready`() {
        assertOpenFailure(skip = FEATURES_FILE, expected = AppError.NotReady)
    }

    @Test
    fun `a truncated features component is corrupt`() {
        assertOpenFailure(mutate = { name, bytes -> if (name == FEATURES_FILE) bytes.copyOf(5) else bytes })
    }

    @Test
    fun `a wrong-version features component is unsupported`() {
        assertOpenFailure(
            expected = AppError.Unsupported,
            mutate = { name, bytes ->
                if (name == FEATURES_FILE) {
                    bytes.copyOf().also { it[VERSION_OFFSET] = (it[VERSION_OFFSET] + 1).toByte() }
                } else {
                    bytes
                }
            },
        )
    }

    @Test
    fun `a past-the-end feature offset is corrupt`() {
        assertOpenFailure(
            mutate = { name, bytes ->
                if (name == FEATURES_FILE) {
                    bytes.copyOf().also { it.fill(0xff.toByte(), FIRST_OFFSET_OFFSET, FIRST_OFFSET_OFFSET + 4) }
                } else {
                    bytes
                }
            },
        )
    }

    private fun assertOpenFailure(
        expected: AppError = AppError.CorruptData,
        skip: String? = null,
        mutate: ((String, ByteArray) -> ByteArray)? = null,
    ) {
        val copy = Files.createTempDirectory("grass-fixture-section")
        try {
            for (name in SECTION_FILES) {
                if (name == skip) {
                    continue
                }
                val bytes = Files.readAllBytes(regionRoot.resolve(name))
                Files.write(copy.resolve(name), mutate?.invoke(name, bytes) ?: bytes)
            }
            val opened = runBlocking { RustPlaceProvider.open(copy.toFile(), REGION_BUILD) }
            assertEquals(expected, errorOf(opened))
        } finally {
            Files.walk(copy).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    companion object {
        private const val REGION = "fixture-mini"
        private const val VERSION = "2026-11-03-1"
        private const val REGION_BUILD = "$REGION-$VERSION"
        private const val CAFE_OSM_ID = 4_815_162_342L
        private const val BENCH_OSM_ID = 4_815_162_344L
        private const val FEATURES_FILE = "features.bin"
        private const val VERSION_OFFSET = 4
        private const val FIRST_OFFSET_OFFSET = 9

        /** The seven search files plus the feature component. */
        private val SECTION_FILES =
            listOf(
                "terms.fst",
                "postings.bin",
                "places.bin",
                "context.bin",
                "entities.bin",
                "kinds.bin",
                "strings.bin",
                FEATURES_FILE,
            )

        private lateinit var regionRoot: Path
        private lateinit var provider: RustPlaceProvider

        @BeforeClass
        @JvmStatic
        fun setUp() {
            regionRoot = repositoryRoot().resolve("test-data/regions/$REGION/$VERSION")
            provider =
                when (val opened = runBlocking { RustPlaceProvider.open(regionRoot.toFile(), REGION_BUILD) }) {
                    is Outcome.Success -> opened.value
                    is Outcome.Failure -> error("cannot open the fixture section: ${opened.error}")
                }
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            provider.close()
        }

        /** The repository root, found by walking up from the test working directory. */
        private fun repositoryRoot(): Path {
            var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            while (directory != null) {
                if (Files.isRegularFile(directory.resolve("settings.gradle.kts")) &&
                    Files.isDirectory(directory.resolve("test-data"))
                ) {
                    return directory
                }
                directory = directory.parent
            }
            error("Cannot find the repository root from ${System.getProperty("user.dir")}")
        }
    }
}
