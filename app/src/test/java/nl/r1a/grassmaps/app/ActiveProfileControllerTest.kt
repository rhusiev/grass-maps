package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.alerts.AlertSettings
import nl.r1a.grassmaps.alerts.AlertSettingsStore
import nl.r1a.grassmaps.alerts.ProfileAlertSettings
import nl.r1a.grassmaps.core.services.RoutingOptionsSettings
import nl.r1a.grassmaps.core.services.UnitSettings
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.provider.RouteOptions
import nl.r1a.grassmaps.plugin.api.provider.RoutePreference
import nl.r1a.grassmaps.plugin.api.service.ProfileValueStore
import nl.r1a.grassmaps.plugin.api.service.ProfileValues
import nl.r1a.grassmaps.plugins.layers.panel.MapStyleChoice
import nl.r1a.grassmaps.ui.shell.GridWidget
import nl.r1a.grassmaps.ui.shell.WidgetLayout
import nl.r1a.grassmaps.ui.shell.WidgetLayoutStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** One profile switch publishes all five stored categories (T-0603). */
class ActiveProfileControllerTest {
    private val style = MapStyleSettings()
    private val units = UnitSettings()
    private val routing = RoutingOptionsSettings()
    private val alerts = AlertSettingsStore()
    private val widgets = WidgetLayoutStore(InMemoryProfileStore())

    private val walk = BuiltInProfiles.WALK
    private val bike = BuiltInProfiles.BIKE

    @Test
    fun switchingTheProfileChangesWidgetsStyleUnitsRoutingAndAlertsTogether() {
        seed(walk, MapStyleChoice.STANDARD, UnitSystem.Metric, RoutePreference.Avoid, false)
        seed(bike, MapStyleChoice.TOURING, UnitSystem.Imperial, RoutePreference.DontMind, true)
        val controller = controller()
        val before = controller.active.value

        controller.activate(bike)
        val after = controller.active.value

        assertEquals(bike, after.profileId)
        assertEquals(MapStyleChoice.TOURING, after.style)
        assertEquals(UnitSystem.Imperial, after.units)
        assertEquals(RoutePreference.DontMind, after.routing.hills)
        assertEquals(true, after.alerts.settings.signal)
        assertEquals(listOf(GridWidget.SpeedCurrent), after.widgets.widgets)
        assertNotEquals(before, after)
    }

    @Test
    fun switchingBackRestoresTheFirstProfileWithoutStaleValues() {
        seed(walk, MapStyleChoice.STANDARD, UnitSystem.Metric, RoutePreference.Avoid, false)
        seed(bike, MapStyleChoice.TOURING, UnitSystem.Imperial, RoutePreference.DontMind, true)
        val controller = controller()

        controller.activate(bike)
        controller.activate(walk)
        val after = controller.active.value

        assertEquals(walk, after.profileId)
        assertEquals(MapStyleChoice.STANDARD, after.style)
        assertEquals(UnitSystem.Metric, after.units)
        assertEquals(RoutePreference.Avoid, after.routing.hills)
        assertEquals(false, after.alerts.settings.signal)
    }

    @Test
    fun thePerStoreActiveFlowsFollowTheSameSwitch() {
        seed(walk, MapStyleChoice.STANDARD, UnitSystem.Metric, RoutePreference.Normal, false)
        seed(bike, MapStyleChoice.TOURING, UnitSystem.Imperial, RoutePreference.Normal, false)
        val controller = controller()

        controller.activate(bike)

        assertEquals("no screen keeps a stale style", MapStyleChoice.TOURING, style.active.value)
        assertEquals("no screen keeps a stale unit", UnitSystem.Imperial, units.units.value)
        assertEquals(bike, alerts.activeProfileId())
    }

    @Test
    fun theActiveProfileIsPublishedToOneState() {
        val controller = controller()

        controller.activate(bike)

        assertEquals(bike, controller.activeProfileId())
        assertEquals(bike, controller.active.value.profileId)
    }

    private fun controller(): ActiveProfileController = ActiveProfileController(style, units, routing, alerts, widgets)

    private fun seed(
        profile: ProfileId,
        choice: MapStyleChoice,
        units: UnitSystem,
        hills: RoutePreference,
        signal: Boolean,
    ) {
        style.select(profile, choice)
        this.units.set(profile, units)
        routing.set(profile, RouteOptions(hills = hills))
        alerts.set(profile, ProfileAlertSettings(settings = AlertSettings(signal = signal)))
        widgets.save(profile, WidgetLayout(listOf(if (signal) GridWidget.SpeedCurrent else GridWidget.Altitude)))
    }

    private class InMemoryProfileStore : ProfileValueStore {
        private var values: ProfileValues = emptyMap()

        override fun read(): Outcome<ProfileValues> = Outcome.Success(values)

        override fun write(values: ProfileValues): Outcome<Unit> {
            this.values = values
            return Outcome.Success(Unit)
        }
    }
}
