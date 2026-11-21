package nl.r1a.grassmaps.regions

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class RoutingMountTest {
    private val base: Path = Files.createTempDirectory("grass-routing-mount")
    private val mountRoot = base.resolve("regions/.routing").toFile()
    private val logs = mutableListOf<String>()

    @After
    fun cleanUp() {
        base.toFile().deleteRecursively()
    }

    @Test
    fun theFirstRegionInIdOrderWinsAnOverlappingTile() {
        val a = region("a", "1", "tiles/2/000/shared.gph" to "from-a", "tiles/2/000/a.gph" to "a")
        val b = region("b", "1", "tiles/2/000/shared.gph" to "from-b", "tiles/2/000/b.gph" to "b")

        val ready = ready(RoutingMount(mountRoot, logs::add).ensure(listOf(b, a)))

        assertEquals(listOf("a", "b"), ready.regions)
        assertEquals("from-a", File(ready.directory, "tiles/2/000/shared.gph").readText())
        assertEquals("a", File(ready.directory, "tiles/2/000/a.gph").readText())
        assertEquals("b", File(ready.directory, "tiles/2/000/b.gph").readText())
        assertEquals("{\"r\":\"a\"}", File(ready.directory, "valhalla.json").readText())
        assertTrue(ready.rebuilt)
    }

    @Test
    fun anUnchangedSetIsNotRebuilt() {
        val a = region("a", "1", "tiles/0/a.gph" to "a")
        val mount = RoutingMount(mountRoot, logs::add)
        val first = ready(mount.ensure(listOf(a)))
        val marker = File(first.directory, "marker").apply { writeText("x") }

        val second = ready(mount.ensure(listOf(a)))

        assertFalse(second.rebuilt)
        assertEquals(first.directory, second.directory)
        assertTrue("an unchanged mount must not be replaced", marker.isFile)
    }

    @Test
    fun aNewRegionOrANewVersionRebuildsIntoAnotherDirectory() {
        val a = region("a", "1", "tiles/0/a.gph" to "a")
        val mount = RoutingMount(mountRoot, logs::add)
        val first = ready(mount.ensure(listOf(a)))

        val withB = ready(mount.ensure(listOf(a, region("b", "1", "tiles/0/b.gph" to "b"))))
        val newVersion = ready(mount.ensure(listOf(region("a", "2", "tiles/0/a.gph" to "a2"))))

        assertTrue(withB.rebuilt && newVersion.rebuilt)
        assertEquals(3, setOf(first.directory, withB.directory, newVersion.directory).size)
        assertEquals("a2", File(newVersion.directory, "tiles/0/a.gph").readText())
    }

    @Test
    fun anUpdatedPackMountsANewViewAndTheOldViewSurvivesTheOldPackBeingDeleted() {
        val old = region("a", "1", "tiles/0/a.gph" to "old")
        val mount = RoutingMount(mountRoot, logs::add)
        val before = ready(mount.ensure(listOf(old)))
        val updated = region("a", "2", "tiles/0/a.gph" to "new")

        val after = ready(mount.ensure(listOf(updated)))
        old.directory.deleteRecursively()

        assertTrue(after.rebuilt)
        assertNotEquals(before.directory, after.directory)
        assertEquals("new", File(after.directory, "tiles/0/a.gph").readText())
        assertEquals("old", File(before.directory, "tiles/0/a.gph").readText())
    }

    @Test
    fun packsFromTwoBuildsStillMountAndTheLogNamesTheSplit() {
        val a = region("a", "1", "tiles/0/a.gph" to "a", build = "build-1")
        val b = region("b", "1", "tiles/0/b.gph" to "b", build = "build-2")

        ready(RoutingMount(mountRoot, logs::add).ensure(listOf(a, b)))

        assertTrue(logs.any { it.contains("mix 2 builds") && it.contains("build-1") && it.contains("build-2") })
    }

    @Test
    fun packsFromOneBuildLogNoSplit() {
        val a = region("a", "1", "tiles/0/a.gph" to "a", build = "build-1")
        val b = region("b", "1", "tiles/0/b.gph" to "b", build = "build-1")

        ready(RoutingMount(mountRoot, logs::add).ensure(listOf(a, b)))

        assertTrue(logs.none { it.contains("mix") })
    }

    @Test
    fun aChangedRoutingHashRebuilds() {
        val mount = RoutingMount(mountRoot, logs::add)
        ready(mount.ensure(listOf(region("a", "1", "tiles/0/a.gph" to "a", sha256 = "aa"))))

        val second = ready(mount.ensure(listOf(region("a", "1", "tiles/0/a.gph" to "a", sha256 = "bb"))))

        assertTrue(second.rebuilt)
    }

    @Test
    fun theMountSharesStorageWithThePackWhenLinksWork() {
        val a = region("a", "1", "tiles/0/a.gph" to "a")
        val ready = ready(RoutingMount(mountRoot, logs::add).ensure(listOf(a)))
        val source = (a.routing as RoutingPart.Directory).directory.toPath().resolve("tiles/0/a.gph")

        assertTrue(Files.isSameFile(source, File(ready.directory, "tiles/0/a.gph").toPath()))
    }

    @Test
    fun aRefusedLinkFallsBackToACopy() {
        val a = region("a", "1", "tiles/0/a.gph" to "a")
        val mount = RoutingMount(mountRoot, logs::add) { _, _ -> throw IOException("no links") }

        val ready = ready(mount.ensure(listOf(a)))

        assertEquals("a", File(ready.directory, "tiles/0/a.gph").readText())
        val source = (a.routing as RoutingPart.Directory).directory.toPath().resolve("tiles/0/a.gph")
        assertFalse(Files.isSameFile(source, File(ready.directory, "tiles/0/a.gph").toPath()))
    }

    @Test
    fun legacyAndUnusablePacksAreSkippedAndReported() {
        val a = region("a", "1", "tiles/0/a.gph" to "a")
        val legacy = a.copy(region = "legacy", routing = RoutingPart.LegacyArchive)
        val unusable = a.copy(region = "unusable", routing = RoutingPart.Unusable)
        val none = a.copy(region = "none", routing = null)

        val ready = ready(RoutingMount(mountRoot, logs::add).ensure(listOf(a, legacy, unusable, none)))

        assertEquals(listOf("a"), ready.regions)
        assertEquals(
            setOf(
                SkippedRouting("legacy", RoutingSkipReason.LegacyArchive),
                SkippedRouting("unusable", RoutingSkipReason.Unusable),
                SkippedRouting("none", RoutingSkipReason.NoRouting),
            ),
            ready.skipped.toSet(),
        )
    }

    @Test
    fun withoutAnyUsablePackNoMountExists() {
        val legacy = region("a", "1", "tiles/0/a.gph" to "a").copy(routing = RoutingPart.LegacyArchive)

        val result = RoutingMount(mountRoot, logs::add).ensure(listOf(legacy))

        assertEquals(
            RoutingMountResult.Unavailable(
                RoutingMountProblem.NoRoutingPacks,
                listOf(SkippedRouting("a", RoutingSkipReason.LegacyArchive)),
            ),
            result,
        )
        assertFalse(mountRoot.exists())
    }

    @Test
    fun packsWithoutAConfigGiveNoMountAndLeaveNoDirectoryBehind() {
        val a = region("a", "1", "tiles/0/a.gph" to "a", withConfig = false)

        val result = RoutingMount(mountRoot, logs::add).ensure(listOf(a))

        assertEquals(RoutingMountProblem.MissingConfig, (result as RoutingMountResult.Unavailable).problem)
        assertEquals(emptyList<String>(), mountRoot.list()?.toList().orEmpty())
    }

    @Test
    fun aLaterPackSuppliesTheConfigWhenTheFirstHasNone() {
        val a = region("a", "1", "tiles/0/a.gph" to "a", withConfig = false)
        val b = region("b", "1", "tiles/0/b.gph" to "b")

        val ready = ready(RoutingMount(mountRoot, logs::add).ensure(listOf(a, b)))

        assertEquals("{\"r\":\"b\"}", File(ready.directory, "valhalla.json").readText())
    }

    @Test
    fun theFirstCallOfAProcessRemovesOldMountsAndLaterCallsKeepThem() {
        val a = region("a", "1", "tiles/0/a.gph" to "a")
        val b = region("b", "1", "tiles/0/b.gph" to "b")
        val stale = File(mountRoot, "mount-0000000000000000").apply { mkdirs() }
        val leftover = File(mountRoot, "build-leftover").apply { mkdirs() }
        val mount = RoutingMount(mountRoot, logs::add)

        val first = ready(mount.ensure(listOf(a)))
        assertFalse(stale.exists() || leftover.exists())
        val second = ready(mount.ensure(listOf(a, b)))

        assertTrue("the view the engine may read must survive a set change", first.directory.isDirectory)
        assertTrue(second.directory.isDirectory)
    }

    @Test
    fun aMountFromEarlierProcessIsReusedWithoutRebuild() {
        val a = region("a", "1", "tiles/0/a.gph" to "a")
        val first = ready(RoutingMount(mountRoot, logs::add).ensure(listOf(a)))

        val again = ready(RoutingMount(mountRoot, logs::add).ensure(listOf(a)))

        assertFalse(again.rebuilt)
        assertEquals(first.directory, again.directory)
    }

    @Test
    fun theMountedPacksAccessReturnsNullWithoutPacks() {
        val regions = base.resolve("regions").toFile().apply { mkdirs() }
        val access = MountedRoutingPacks(regions)

        assertNull(access.packDirectory())
    }

    private fun ready(result: RoutingMountResult): RoutingMountResult.Ready {
        assertTrue("expected Ready, got $result", result is RoutingMountResult.Ready)
        return result as RoutingMountResult.Ready
    }

    private fun region(
        id: String,
        version: String,
        vararg tiles: Pair<String, String>,
        withConfig: Boolean = true,
        sha256: String = "hash-$id-$version",
        build: String? = null,
    ): MountedRegion {
        val directory = base.resolve("packs/$id/$version").toFile()
        val routing = File(directory, "routing")
        tiles.forEach { (path, content) -> File(routing, path).apply { parentFile?.mkdirs() }.writeText(content) }
        if (withConfig) {
            File(routing, "valhalla.json").writeText("{\"r\":\"$id\"}")
        }
        val manifest = RegionManifest(id, version, 1, "2026-01-01T00:00:00Z", null, emptyList())
        return MountedRegion(
            id,
            version,
            directory,
            File(directory, "map.pmtiles"),
            null,
            manifest,
            RoutingPart.Directory(routing, sha256, build),
        )
    }
}
