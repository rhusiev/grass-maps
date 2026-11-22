package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.alerts.AlertSettingsStore
import nl.r1a.grassmaps.core.services.ProfileCatalog
import nl.r1a.grassmaps.core.services.ProfileSettingsService
import nl.r1a.grassmaps.core.services.RoutingOptionsSettings
import nl.r1a.grassmaps.core.services.UnitSettings
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.service.ProfileSettingsGroup
import nl.r1a.grassmaps.plugins.layers.panel.MapStyleChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Style and units join copy, reset and undo now that they are per-profile (T-0604). */
class ProfileSettingsStyleUnitsTest {
    private val style = MapStyleSettings()
    private val units = UnitSettings()
    private val service =
        ProfileSettingsService(
            ProfileCatalog(),
            profileSettingsGroups(style, units, RoutingOptionsSettings(), AlertSettingsStore()),
        )

    @Test
    fun theProfileActionsOfferStyleAndUnits() {
        val groups = (service.state() as Outcome.Success).value.groups

        assertTrue(groups.contains(ProfileSettingsGroup.STYLE))
        assertTrue(groups.contains(ProfileSettingsGroup.UNITS))
    }

    @Test
    fun copyingTheTwoGroupsCopiesTheStyleAndTheUnits() {
        style.select(BuiltInProfiles.WALK, MapStyleChoice.TRAIL)
        units.set(BuiltInProfiles.WALK, UnitSystem.Imperial)

        val result = service.copySettings("walk", "bike", setOf(ProfileSettingsGroup.STYLE, ProfileSettingsGroup.UNITS))

        assertTrue(result is Outcome.Success)
        assertEquals(MapStyleChoice.TRAIL, style.forProfile(BuiltInProfiles.BIKE))
        assertEquals(UnitSystem.Imperial, units.forProfile(BuiltInProfiles.BIKE))
    }

    @Test
    fun resettingTheTwoGroupsRestoresDefaultsAndUndoBringsThemBack() {
        style.select(BuiltInProfiles.BIKE, MapStyleChoice.TRAIL)
        units.set(BuiltInProfiles.BIKE, UnitSystem.Imperial)

        assertTrue(service.reset("bike", setOf(ProfileSettingsGroup.STYLE, ProfileSettingsGroup.UNITS)) is Outcome.Success)
        assertEquals(MapStyleChoice.MEADOW, style.forProfile(BuiltInProfiles.BIKE))
        assertEquals(UnitSystem.Metric, units.forProfile(BuiltInProfiles.BIKE))

        assertTrue(service.undo() is Outcome.Success)
        assertEquals(MapStyleChoice.TRAIL, style.forProfile(BuiltInProfiles.BIKE))
        assertEquals(UnitSystem.Imperial, units.forProfile(BuiltInProfiles.BIKE))
    }

    @Test
    fun undoingACopyRestoresTheEarlierStyleAndUnits() {
        style.select(BuiltInProfiles.WALK, MapStyleChoice.TRAIL)
        units.set(BuiltInProfiles.WALK, UnitSystem.Imperial)

        service.copySettings("walk", "bike", setOf(ProfileSettingsGroup.STYLE, ProfileSettingsGroup.UNITS))
        assertTrue(service.undo() is Outcome.Success)

        assertEquals(MapStyleChoice.MEADOW, style.forProfile(BuiltInProfiles.BIKE))
        assertEquals(UnitSystem.Metric, units.forProfile(BuiltInProfiles.BIKE))
    }
}
