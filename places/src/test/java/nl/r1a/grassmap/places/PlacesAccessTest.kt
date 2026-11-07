package nl.r1a.grassmap.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlacesAccessTest {
    private val places = PlacesAccess(externalFilesRoot = java.io.File("/tmp/places-root"))

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
}
