package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** The directory branch of [PackageVerifier]; file components are covered by the installer tests. */
class PackageVerifierTest {
    private val root: Path = Files.createTempDirectory("grass-verifier")

    @After
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun anIntactDirectoryComponentVerifies() {
        val manifest = writeRouting()

        assertTrue(verify(manifest) is Outcome.Success)
    }

    @Test
    fun aTamperedFileIsCorrupt() {
        val manifest = writeRouting()
        Files.writeString(root.resolve("routing/tiles/0/003/110.gph"), "tile-X")

        assertCorrupt(verify(manifest))
    }

    @Test
    fun aFileOfAnotherSizeIsCorrupt() {
        val manifest = writeRouting()
        Files.writeString(root.resolve("routing/tiles/0/003/110.gph"), "tile-aa")

        assertCorrupt(verify(manifest))
    }

    @Test
    fun anExtraFileInTheDirectoryIsCorrupt() {
        val manifest = writeRouting()
        write("routing/tiles/2/000/999.gph", "extra")

        assertCorrupt(verify(manifest))
    }

    @Test
    fun aMissingFileIsCorrupt() {
        val manifest = writeRouting()
        Files.delete(root.resolve("routing/valhalla.json"))

        assertCorrupt(verify(manifest))
    }

    @Test
    fun aRenamedFileIsCorrupt() {
        val manifest = writeRouting()
        Files.move(root.resolve("routing/valhalla.json"), root.resolve("routing/other.json"))

        assertCorrupt(verify(manifest))
    }

    @Test
    fun aSymbolicLinkInTheDirectoryIsCorrupt() {
        val manifest = writeRouting()
        Files.createSymbolicLink(root.resolve("routing/tiles/link.gph"), root.resolve("routing/valhalla.json"))

        assertCorrupt(verify(manifest))
    }

    @Test
    fun aFileOutsideTheDirectoryIsStillAnExtraFile() {
        val manifest = writeRouting()
        write("stray.bin", "x")

        assertCorrupt(verify(manifest))
    }

    @Test
    fun anEmptyDirectoryComponentIsCorrupt() {
        Files.createDirectories(root.resolve("routing"))
        val manifest = manifestOf(component(bytes = 0, sha256 = ComponentListing.digest(emptyList())))

        assertCorrupt(verify(manifest))
    }

    private fun writeRouting(): RegionManifest {
        write("routing/tiles/0/003/110.gph", "tile-a")
        write("routing/valhalla.json", "{}")
        val files = (ComponentListing.scan(root.resolve("routing").toFile(), CancellationHandle.None) as Outcome.Success).value
        return manifestOf(component(ComponentListing.totalBytes(files), ComponentListing.digest(files)))
    }

    private fun component(
        bytes: Long,
        sha256: String,
    ) = RegionComponent(
        kind = RoutingLayout.KIND,
        file = "routing",
        format = RoutingLayout.FORMAT,
        formatVersion = RoutingLayout.FORMAT_VERSION,
        version = "v1",
        bytes = bytes,
        sha256 = sha256,
        standIn = false,
        zoomLevels = emptyList(),
        isDirectory = true,
    )

    private fun manifestOf(component: RegionComponent) = RegionManifest("r", "v1", 1, "2026-01-01T00:00:00Z", null, listOf(component))

    private fun verify(manifest: RegionManifest): Outcome<Unit> = PackageVerifier().verify(root.toFile(), manifest, CancellationHandle.None)

    private fun assertCorrupt(outcome: Outcome<Unit>) {
        assertTrue("expected corrupt, got $outcome", outcome is Outcome.Failure)
        assertEquals(AppError.CorruptData, (outcome as Outcome.Failure).error)
    }

    private fun write(
        relative: String,
        content: String,
    ) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
