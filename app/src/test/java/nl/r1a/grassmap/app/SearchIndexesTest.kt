package nl.r1a.grassmap.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.places.PlacesAccess
import nl.r1a.grassmap.plugin.api.model.InstalledPack
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import nl.r1a.grassmap.regions.RegionMountResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The search index the app queries follows the mounted packs, not a fixed
 * name: the first pack in region-id order is the active index, the same pack
 * the map and the single-region readers treat as active.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchIndexesTest {
    private val root: Path = Files.createTempDirectory("grass-search-indexes")
    private val store: Path = root.resolve("regions").also { Files.createDirectories(it) }
    private val external: Path = root.resolve("external").also { Files.createDirectories(it) }
    private val installed = MutableStateFlow<List<InstalledPack>>(emptyList())
    private var mounts = 0

    @After
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun theActiveIndexIsThePackTheAppAlreadyTreatsAsActive() =
        runTest {
            install("ua-46")
            install("ua-65")
            installed.value = listOf(pack("ua-46"), pack("ua-65"))
            val indexes = indexes(backgroundScope)

            indexes.start()

            val active = requireNotNull(indexes.state.value.active) { "no active index" }
            assertEquals("ua-46", active.name)
            assertEquals(versionDirectory("ua-46").toFile(), active.directory)
            assertNotEquals(HARD_CODED_INDEX, active.name)
            assertEquals(
                listOf("ua-46", "ua-65"),
                indexes.state.value.indexes
                    .map { it.name },
            )
        }

    @Test
    fun aPackInstalledAfterStartBecomesSearchable() =
        runTest {
            val indexes = indexes(backgroundScope)
            indexes.start()
            assertNull(indexes.state.value.active)

            install("ua-65")
            installed.value = listOf(pack("ua-65"))
            runCurrent()
            assertEquals("the follower must mount the store again", 2, mounts)

            val active = requireNotNull(indexes.state.value.active) { "the new pack is not searchable" }
            assertEquals("ua-65", active.name)
            assertEquals(versionDirectory("ua-65").toFile(), active.directory)
        }

    @Test
    fun aPackWithoutASearchSectionIsNotTheIndex() =
        runTest {
            install("ua-46", withSearch = false)
            install("ua-65")
            installed.value = listOf(pack("ua-46"), pack("ua-65"))
            val indexes = indexes(backgroundScope)

            indexes.start()

            assertEquals(
                "ua-65",
                indexes.state.value.active
                    ?.name,
            )
            assertNull("a pack without a section cannot be opened by name", indexes.directory("ua-46"))
        }

    @Test
    fun aLookupDoesNotMountTheStoreAgain() =
        runTest {
            install("ua-65")
            installed.value = listOf(pack("ua-65"))
            val indexes = indexes(backgroundScope)
            indexes.start()
            runCurrent()
            val mountsBefore = mounts

            repeat(LOOKUPS) {
                assertEquals(versionDirectory("ua-65").toFile(), indexes.directory("ua-65"))
            }

            assertEquals("a keystroke must not re-read the store", mountsBefore, mounts)
        }

    @Test
    fun theSearchAccessOpensThePacksSectionUnderItsRegionName() =
        runTest {
            install("ua-65")
            installed.value = listOf(pack("ua-65"))
            val indexes = indexes(backgroundScope)
            indexes.start()
            val places =
                PlacesAccess(
                    externalFilesRoot = external.toFile(),
                    packIndex = indexes::directory,
                )

            assertEquals(versionDirectory("ua-65").toFile(), places.indexDirectory("ua-65"))
            assertEquals(
                "the hard-coded name keeps only the external path",
                File(File(external.toFile(), "search"), HARD_CODED_INDEX),
                places.indexDirectory(HARD_CODED_INDEX),
            )
        }

    private fun indexes(scope: CoroutineScope): SearchIndexes =
        SearchIndexes(
            root = { store.toFile() },
            installed = { installed },
            scope = scope,
            mount = { file ->
                mounts += 1
                RegionMountResolver(file).resolveAll()
            },
            log = { message -> println("SI: $message") },
        )

    private fun pack(region: String): InstalledPack = InstalledPack(region = region, version = TestRegions.DEFAULT_VERSION)

    private fun versionDirectory(region: String): Path = store.resolve(region).resolve(TestRegions.DEFAULT_VERSION)

    /**
     * Installs the committed fixture package under [region]. With [withSearch]
     * false the section's `terms.fst` is left out while the manifest still
     * declares it, which is the pack whose search section is broken.
     */
    private fun install(
        region: String,
        withSearch: Boolean = true,
    ) {
        val source = TestRegions.load().root
        val target = Files.createDirectories(versionDirectory(region))
        Files.list(source).use { entries ->
            entries.forEach { file ->
                val name = file.fileName.toString()
                if (!withSearch && name == MISSING_SEARCH_FILE) {
                    return@forEach
                }
                val bytes = Files.readAllBytes(file)
                val content =
                    if (name == "manifest.json") {
                        String(bytes, Charsets.UTF_8)
                            .replace("\"region\": \"${TestRegions.DEFAULT_REGION}\"", "\"region\": \"$region\"")
                            .toByteArray(Charsets.UTF_8)
                    } else {
                        bytes
                    }
                Files.write(target.resolve(name), content)
            }
        }
        Files.writeString(store.resolve(region).resolve("current"), TestRegions.DEFAULT_VERSION + "\n")
    }

    private companion object {
        const val HARD_CODED_INDEX = "lviv"
        const val MISSING_SEARCH_FILE = "terms.fst"
        const val LOOKUPS = 100
    }
}
