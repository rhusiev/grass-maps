package nl.r1a.grassmaps.regions

import nl.r1a.grassmaps.plugin.api.contract.CancellationHandle
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ComponentListingTest {
    private val root: Path = Files.createTempDirectory("grass-listing")

    @After
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun theListingMatchesTheVectorThePipelineTestChecks() {
        write("tiles/0/003/110.gph", "tile-a")
        write("valhalla.json", "{}")

        val files = scan()

        assertEquals(
            "tiles/0/003/110.gph\t6\t$TILE_A_SHA\nvalhalla.json\t2\t$CONFIG_SHA\n",
            ComponentListing.text(files),
        )
        assertEquals(VECTOR_LISTING_SHA, ComponentListing.digest(files))
        assertEquals(8L, ComponentListing.totalBytes(files))
    }

    @Test
    fun theOrderIsByUtf8BytesNotByCreationOrUtf16() {
        write("b.gph", "1")
        write("a/z.gph", "1")
        write("a.gph", "1")
        write("😀.gph", "1")
        write("～.gph", "1")

        val paths = scan().map { it.path }

        assertEquals(listOf("a.gph", "a/z.gph", "b.gph", "～.gph", "😀.gph"), paths)
    }

    @Test
    fun aSymbolicLinkIsRefused() {
        write("real.gph", "x")
        Files.createSymbolicLink(root.resolve("link.gph"), root.resolve("real.gph"))

        assertCorrupt()
    }

    @Test
    fun aControlCharacterInAPathIsRefused() {
        write("bad\nname.gph", "x")

        assertCorrupt()
    }

    private fun assertCorrupt() {
        val result = ComponentListing.scan(root.toFile(), CancellationHandle.None)
        assertTrue(result is Outcome.Failure && result.error == AppError.CorruptData)
    }

    private fun scan(): List<ListedFile> = (ComponentListing.scan(root.toFile(), CancellationHandle.None) as Outcome.Success).value

    private fun write(
        relative: String,
        content: String,
    ) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private companion object {
        const val TILE_A_SHA = "30e5f1e49470e0e241c3d7c75b2b8be5b246abd8268686363a8d092d90a728f4"
        const val CONFIG_SHA = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
        const val VECTOR_LISTING_SHA = "3ce123a83370fdc1ba033e1c8951f66e6bdd2e1ee61e9459643165301ae0e7d5"
    }
}
