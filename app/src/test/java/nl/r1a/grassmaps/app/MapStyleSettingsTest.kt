package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.plugins.layers.panel.MapStyleChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.util.UUID

/** The style is one stored value per profile, migrated from the old app-wide file (T-0603). */
class MapStyleSettingsTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "style-settings-${UUID.randomUUID()}")
    private val storeFile = File(root, "profiles/map-style.json")
    private val legacyFile = File(root, "settings/style.json")

    @After
    fun removeRoot() {
        root.deleteRecursively()
    }

    @Test
    fun theMapStartsOnMeadowWhenNothingWasSaved() {
        assertEquals(MapStyleChoice.STANDARD, MapStyleSettings().current())
    }

    @Test
    fun switchingToTrailChangesTheActiveStyleState() {
        val settings = MapStyleSettings()

        assertEquals(Outcome.Success(Unit), settings.select(MapStyleChoice.TOURING))

        assertEquals(MapStyleChoice.TOURING, settings.active.value)
    }

    @Test
    fun theChoicePersistsAcrossANewInstance() {
        MapStyleSettings.open(storeFile, legacyFile).select(MapStyleChoice.TOURING)

        assertEquals(MapStyleChoice.TOURING, MapStyleSettings.open(storeFile, legacyFile).current())
        assertEquals(MapStyleChoice.TOURING, MapStyleSettings.open(storeFile, legacyFile).forProfile(BuiltInProfiles.WALK))
    }

    @Test
    fun anUnknownOrCorruptValueFallsBackToMeadow() {
        storeFile.parentFile?.mkdirs()
        storeFile.writeText("""{"schema_version":1,"profiles":{"walk":{"style":"touring-dark"}}}""")
        assertEquals(MapStyleChoice.STANDARD, MapStyleSettings.open(storeFile, legacyFile).current())

        storeFile.writeText("{not json")
        assertEquals(MapStyleChoice.STANDARD, MapStyleSettings.open(storeFile, legacyFile).current())
    }

    @Test
    fun theLegacyAppWideChoiceMovesToTheWalkProfile() {
        legacyFile.parentFile?.mkdirs()
        legacyFile.writeText("""{"style":"touring"}""")

        val settings = MapStyleSettings.open(storeFile, legacyFile)

        assertEquals(MapStyleChoice.TOURING, settings.forProfile(BuiltInProfiles.WALK))
        assertEquals(MapStyleChoice.STANDARD, settings.forProfile(BuiltInProfiles.BIKE))
        assertFalse(legacyFile.isFile)
    }

    @Test
    fun activatingAProfilePublishesItsStyle() {
        val settings = MapStyleSettings.open(storeFile, legacyFile)
        settings.select(ProfileId("bike"), MapStyleChoice.TOURING)

        settings.activate(ProfileId("bike"))
        assertEquals(MapStyleChoice.TOURING, settings.active.value)
        settings.activate(BuiltInProfiles.WALK)
        assertEquals(MapStyleChoice.STANDARD, settings.active.value)
    }
}
