package nl.r1a.grassmaps.core.services

import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** The six screen rows persist per profile, like the other settings (T-0111). */
class ScreenSettingsTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "screen-settings-${UUID.randomUUID()}")
    private val storeFile = File(root, "profiles/screens.json")
    private val bike = ProfileId("bike")

    @After
    fun removeRoot() {
        root.deleteRecursively()
    }

    @Test
    fun defaultsAreSystemAndKeepTheMapAnimations() {
        assertEquals(ScreenSettingsValues(), ScreenSettings().current())
    }

    @Test
    fun everyRowPersistsAcrossANewInstance() {
        val chosen =
            ScreenSettingsValues(
                appTheme = AppThemeChoice.DARK,
                nightMap = NightMapMode.BY_SUN,
                keepScreenOn = true,
                animationsEnabled = false,
                language = UiLanguage.UKRAINIAN,
            )
        val settings = ScreenSettings.open(storeFile)
        for ((key, value) in chosen.asStoredValues()) {
            assertEquals(Outcome.Success(Unit), settings.set(key, value))
        }

        assertEquals(chosen, ScreenSettings.open(storeFile).current())
    }

    @Test
    fun aProfileKeepsItsOwnRowsAndCopyCarriesThem() {
        val settings = ScreenSettings.open(storeFile)
        settings.set(ScreenSettings.APP_THEME_KEY, AppThemeChoice.DARK.wire)

        assertEquals(ScreenSettingsValues(appTheme = AppThemeChoice.DARK), settings.current())
        assertEquals(ScreenSettingsValues(), settings.forProfile(bike))

        settings.activate(bike)
        assertEquals(ScreenSettingsValues(), settings.current())
        settings.activate(BuiltInProfiles.WALK)
        assertEquals(ScreenSettingsValues(appTheme = AppThemeChoice.DARK), settings.current())

        assertTrue(settings.read(BuiltInProfiles.WALK).isNotEmpty())
        assertEquals(Outcome.Success(Unit), settings.replace(bike, settings.read(BuiltInProfiles.WALK)))
        assertEquals(ScreenSettingsValues(appTheme = AppThemeChoice.DARK), settings.forProfile(bike))
    }

    @Test
    fun unknownAndCorruptValuesFallBackToDefaults() {
        storeFile.parentFile?.mkdirs()
        storeFile.writeText("""{"schema_version":1,"profiles":{"walk":{"app_theme":"neon","language":"xx"}}}""")
        assertEquals(ScreenSettingsValues(), ScreenSettings.open(storeFile).current())

        storeFile.writeText("{not json")
        assertEquals(ScreenSettingsValues(), ScreenSettings.open(storeFile).current())
    }
}
