package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** The persisted search history switch (T-0207). */
class SearchHistorySettingsTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "search-history-settings-${UUID.randomUUID()}")
    private val file = File(File(root, "settings"), "search-history.json")

    @After
    fun removeRoot() {
        root.setWritable(true)
        root.deleteRecursively()
    }

    @Test
    fun `without a file history is on`() {
        val settings = SearchHistorySettings()

        assertEquals(true, settings.isEnabled())
    }

    @Test
    fun `off is stored and read back by a new instance`() {
        val settings = SearchHistorySettings(file)
        assertEquals(true, settings.isEnabled())

        assertEquals(Outcome.Success(Unit), settings.set(false))

        assertEquals(false, settings.isEnabled())
        assertEquals(false, SearchHistorySettings(file).isEnabled())
    }

    @Test
    fun `on is stored after off`() {
        SearchHistorySettings(file).set(false)

        SearchHistorySettings(file).set(true)

        assertEquals(true, SearchHistorySettings(file).isEnabled())
    }

    @Test
    fun `a corrupt file reads as on without an error`() {
        file.parentFile?.mkdirs()
        file.writeText("not json at all")

        assertEquals(true, SearchHistorySettings(file).isEnabled())

        file.writeText("""{"enabled":"maybe"}""")

        assertEquals(true, SearchHistorySettings(file).isEnabled())
    }

    @Test
    fun `an unwritable target is a typed failure and keeps the new value`() {
        root.mkdirs()
        val blocked = File(root, "search-history.json")
        blocked.mkdirs()
        val settings = SearchHistorySettings(blocked)

        val outcome = settings.set(false)

        assertTrue(outcome is Outcome.Failure)
        assertTrue((outcome as Outcome.Failure).error is AppError.Internal)
        assertEquals(false, settings.isEnabled())
    }
}
