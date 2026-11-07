package nl.r1a.grassmap.places

import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Extracts the committed Lviv search section once per test JVM. The files are
 * the P4a spike output (10.1 MiB); the reader mmaps them, so a copy per class
 * would map the same bytes twice for no gain.
 */
internal object TestSearchIndex {
    private val indexFiles =
        listOf(
            "terms.fst",
            "postings.bin",
            "places.bin",
            "context.bin",
            "entities.bin",
            "kinds.bin",
            "strings.bin",
        )

    val directory: Path by lazy {
        val root = Files.createTempDirectory("grass-search-lviv")
        for (name in indexFiles) {
            val source =
                requireNotNull(TestSearchIndex::class.java.getResourceAsStream("/search/lviv/$name")) {
                    "missing /search/lviv/$name"
                }
            source.use { input ->
                Files.copy(input, root.resolve(name), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        root
    }

    fun open(regionBuild: String = "lviv-test"): RustPlaceProvider =
        when (val opened = runBlocking { RustPlaceProvider.open(directory.toFile(), regionBuild) }) {
            is Outcome.Success -> opened.value
            is Outcome.Failure -> error("cannot open the Lviv index: ${opened.error}")
        }
}
