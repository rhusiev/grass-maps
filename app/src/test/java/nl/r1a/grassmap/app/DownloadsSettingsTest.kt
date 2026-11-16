package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.MobileDataPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadsSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "settings/downloads.json") }

    @Test
    fun automaticUpdatesDefaultToOnWithoutAFileOrAFileFromAnEarlierVersion() {
        assertTrue(DownloadsSettings(file).loadAutoUpdate())
        DownloadsSettings(file).save(MobileDataPolicy.ANY)
        file.writeText("""{"mobileData":"ANY"}""")

        assertTrue(DownloadsSettings(file).loadAutoUpdate())
    }

    @Test
    fun theAutoUpdateChoiceSurvivesARestartAndKeepsTheMobileDataPolicy() {
        val settings = DownloadsSettings(file)
        settings.save(MobileDataPolicy.ANY)

        settings.saveAutoUpdate(false)

        val restarted = DownloadsSettings(file)
        assertFalse(restarted.loadAutoUpdate())
        assertEquals(MobileDataPolicy.ANY, restarted.load())
    }

    @Test
    fun savingThePolicyKeepsTheAutoUpdateChoice() {
        val settings = DownloadsSettings(file)
        settings.saveAutoUpdate(false)

        settings.save(MobileDataPolicy.WIFI_ONLY)

        assertFalse(DownloadsSettings(file).loadAutoUpdate())
        assertEquals(MobileDataPolicy.WIFI_ONLY, DownloadsSettings(file).load())
    }
}
