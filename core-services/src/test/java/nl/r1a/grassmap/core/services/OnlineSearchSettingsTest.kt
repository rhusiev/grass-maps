package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.UUID

/** The separate position allowance starts off and survives a restart. */
class OnlineSearchSettingsTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "online-search-settings-${UUID.randomUUID()}")
    private val file = File(File(root, "settings"), "online-search.json")

    @After
    fun removeRoot() {
        root.setWritable(true)
        root.deleteRecursively()
    }

    @Test
    fun `without a file the position is not sent`() {
        assertEquals(false, OnlineSearchSettings().isPositionAllowed())
    }

    @Test
    fun `allowing the position is stored and read back by a new instance`() {
        val settings = OnlineSearchSettings(file)

        assertEquals(Outcome.Success(Unit), settings.setPositionAllowed(true))

        assertEquals(true, settings.isPositionAllowed())
        assertEquals(true, OnlineSearchSettings(file).isPositionAllowed())
    }

    @Test
    fun `turning the allowance off is stored too`() {
        OnlineSearchSettings(file).setPositionAllowed(true)

        OnlineSearchSettings(file).setPositionAllowed(false)

        assertEquals(false, OnlineSearchSettings(file).isPositionAllowed())
    }

    @Test
    fun `a corrupt file reads as off`() {
        file.parentFile?.mkdirs()
        file.writeText("not json at all")

        assertEquals(false, OnlineSearchSettings(file).isPositionAllowed())

        file.writeText("""{"positionAllowed":"maybe"}""")

        assertEquals(false, OnlineSearchSettings(file).isPositionAllowed())
    }
}
