package nl.r1a.grassmap.places

import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Extracts a committed search section once per test JVM. The files are the
 * reader's section; it mmaps them, so a copy per class would map the same bytes
 * twice for no gain.
 *
 * `format.bin` is optional: the Lviv P4a section is version 1 and has no
 * header, while a section built now is version 2 and carries one.
 */
internal object TestSearchIndex {
    private val requiredIndexFiles =
        listOf(
            "terms.fst",
            "postings.bin",
            "places.bin",
            "context.bin",
            "entities.bin",
            "kinds.bin",
            "strings.bin",
            "features.bin",
        )
    private val optionalIndexFiles = listOf("format.bin")

    val directory: Path by lazy { extract("/search/lviv") }

    val addressesDirectory: Path by lazy { extract("/search/lviv-addresses") }

    fun open(regionBuild: String = "lviv-test"): RustPlaceProvider = open(directory, regionBuild)

    fun openAddresses(regionBuild: String = "lviv-addresses-test"): RustPlaceProvider = open(addressesDirectory, regionBuild)

    private fun open(
        root: Path,
        regionBuild: String,
    ): RustPlaceProvider =
        when (val opened = runBlocking { RustPlaceProvider.open(root.toFile(), regionBuild) }) {
            is Outcome.Success -> opened.value
            is Outcome.Failure -> error("cannot open the search index at $root: ${opened.error}")
        }

    private fun extract(resourceDirectory: String): Path {
        val root = Files.createTempDirectory("grass-search")
        for (name in requiredIndexFiles) {
            copy(resourceDirectory, name, root) ?: error("missing $resourceDirectory/$name")
        }
        for (name in optionalIndexFiles) {
            copy(resourceDirectory, name, root)
        }
        return root
    }

    private fun copy(
        resourceDirectory: String,
        name: String,
        destination: Path,
    ): Unit? {
        val source =
            TestSearchIndex::class.java.getResourceAsStream("$resourceDirectory/$name")
                ?: return null
        source.use { input ->
            Files.copy(input, destination.resolve(name), StandardCopyOption.REPLACE_EXISTING)
        }
        return Unit
    }
}
