package nl.r1a.grassmap.places

import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PlacesAccessTest {
    private val places = PlacesAccess(externalFilesRoot = File("/tmp/places-root"))

    @Test
    fun anIndexNameIsOneDirectoryUnderTheSearchRoot() {
        assertNotNull(places.indexDirectory("lviv"))
        assertNotNull(places.indexDirectory("ua-46_2026.11"))
    }

    @Test
    fun anIndexNameCannotEscapeTheSearchRoot() {
        assertNull(places.indexDirectory(".."))
        assertNull(places.indexDirectory("."))
        assertNull(places.indexDirectory("../escape"))
        assertNull(places.indexDirectory("/absolute"))
        assertNull(places.indexDirectory("a/b"))
        assertNull(places.indexDirectory(""))
        assertEquals(null, PlacesAccess(externalFilesRoot = null).indexDirectory("lviv"))
    }

    @Test
    fun aPackSectionWinsOverTheExternalSearchRoot() {
        val pack = Files.createTempDirectory("places-pack-section").toFile()
        try {
            val access =
                PlacesAccess(
                    externalFilesRoot = File("/tmp/places-root"),
                    packIndex = { name -> pack.takeIf { name == PACK_REGION } },
                )

            assertEquals(pack, access.indexDirectory(PACK_REGION))
            assertNotNull("an external index keeps working", access.indexDirectory("lviv"))
        } finally {
            pack.deleteRecursively()
        }
    }

    @Test
    fun anEmptyIndexNameHasNothingToOpen() =
        runBlocking {
            assertEquals(Outcome.Failure(AppError.NotReady), places.provider(""))
        }

    private companion object {
        const val PACK_REGION = "ua-65"
    }
}
