package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.model.InstalledPack
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The map's sources follow the packs the region store publishes: an install or
 * a remove resolves them again and publishes the new set, and a publication
 * that names the packs the map already serves resolves nothing. The store
 * republishes its state on every progress tick and catalogue refresh, so the
 * second half is what keeps a resolve off that path.
 */
class MapSourceUpdatesTest {
    private val root: Path = Files.createTempDirectory("grass-source-updates")
    private val store: Path = root.resolve("regions").also { Files.createDirectories(it) }

    @After
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun aSetTheSourcesAlreadyServeIsNotResolved() =
        runTest {
            install("ua-46")
            val initial = resolveSources()
            var resolves = 0

            val published =
                flow {
                    emit(listOf(pack("ua-46")))
                    emit(listOf(pack("ua-46")))
                }
            val shown =
                mapSourcesFollowing(initial, published) {
                    resolves += 1
                    resolveSources()
                }.toList()

            assertEquals("the packs the map serves are not a change", 0, resolves)
            assertTrue(shown.isEmpty())
        }

    @Test
    fun anInstallResolvesOnceAndPublishesTheJoinedSources() =
        runTest {
            install("ua-46")
            val initial = resolveSources()
            var resolves = 0

            val published =
                flow {
                    emit(listOf(pack("ua-46")))
                    install("ua-61")
                    emit(listOf(pack("ua-46"), pack("ua-61")))
                    emit(listOf(pack("ua-46"), pack("ua-61")))
                }
            val shown =
                mapSourcesFollowing(initial, published) {
                    resolves += 1
                    resolveSources()
                }.toList()

            assertEquals("one install is one resolve", 1, resolves)
            val sources = shown.single()
            assertEquals("regions:ua-46,ua-61", sources.regionName)
            assertEquals(
                listOf(ServedPack("ua-46", VERSION), ServedPack("ua-61", VERSION)),
                sources.packs,
            )
        }

    @Test
    fun aRemoveResolvesAndPublishesTheFallback() =
        runTest {
            install("ua-46")
            val initial = resolveSources()
            var resolves = 0

            val published =
                flow {
                    store.resolve("ua-46").toFile().deleteRecursively()
                    emit(emptyList<InstalledPack>())
                }
            val shown =
                mapSourcesFollowing(initial, published) {
                    resolves += 1
                    resolveSources()
                }.toList()

            assertEquals(1, resolves)
            val sources = shown.single()
            assertTrue("a removed pack is no longer served", sources.packs.isEmpty())
            assertEquals("none", sources.regionName)
        }

    @Test
    fun aResolveThatStillServesTheOldPacksIsNotPublished() =
        runTest {
            install("ua-46")
            val initial = resolveSources()
            install("ua-61")
            val joined = resolveSources()
            var resolves = 0

            val published =
                flow {
                    emit(listOf(pack("ua-46"), pack("ua-61")))
                    emit(listOf(pack("ua-46"), pack("ua-61")))
                }
            val shown =
                mapSourcesFollowing(initial, published) {
                    resolves += 1
                    if (resolves == 1) initial else joined
                }.toList()

            assertEquals(2, resolves)
            assertEquals("regions:ua-46,ua-61", shown.single().regionName)
        }

    private fun resolveSources(): MapSources =
        MapSources.resolve(
            regionsRoot = store.toFile(),
            directories = emptyList(),
            fixture = { null },
        )

    private fun install(region: String) = installFixturePack(store, region)

    private fun pack(region: String): InstalledPack = InstalledPack(region = region, version = VERSION)

    private companion object {
        val VERSION = TestRegions.DEFAULT_VERSION
    }
}
