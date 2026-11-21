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
import org.junit.Test

/** One tap of the map switcher cycles the profiles and publishes all five stores (T-0604). */
class QuickProfileSwitchTest {
    private val style = MapStyleSettings()
    private val units = UnitSettings()
    private val routing = RoutingOptionsSettings()
    private val alerts = AlertSettingsStore()
    private val widgets = WidgetLayoutStore(InMemoryProfileStore())
    private val walk = BuiltInProfiles.WALK
    private val bike = BuiltInProfiles.BIKE
    private val hike = BuiltInProfiles.HIKE

    @Test
    fun oneTapMovesToTheNextProfileAndWrapsAround() {
        val switch = switch(BuiltInProfiles.ALL)

        assertEquals(bike, switch.next())
        assertEquals(hike, switch.next())
        assertEquals(walk, switch.next())
    }

    @Test
    fun oneTapPublishesAllFiveCategoriesFromTheNextProfile() {
        seed(walk, MapStyleChoice.STANDARD, UnitSystem.Metric, RoutePreference.Avoid, false)
        seed(bike, MapStyleChoice.TOURING, UnitSystem.Imperial, RoutePreference.DontMind, true)
        val controller = controller()
        val switch = switch(BuiltInProfiles.ALL, controller)

        switch.next()
        val active = controller.active.value

        assertEquals(bike, active.profileId)
        assertEquals(MapStyleChoice.TOURING, active.style)
        assertEquals(UnitSystem.Imperial, active.units)
        assertEquals(RoutePreference.DontMind, active.routing.hills)
        assertEquals(true, active.alerts.settings.signal)
        assertEquals(listOf(GridWidget.SpeedCurrent), active.widgets.widgets)
    }

    @Test
    fun theCycleIncludesAUserProfileInCatalogOrder() {
        val gravel = ProfileId("gravel")
        val switch = switch(listOf(walk, bike, hike, gravel))

        assertEquals(bike, switch.next())
        assertEquals(hike, switch.next())
        assertEquals(gravel, switch.next())
        assertEquals(walk, switch.next())
    }

    private fun switch(
        order: List<ProfileId>,
        controller: ActiveProfileController = controller(),
    ): QuickProfileSwitch = QuickProfileSwitch(controller) { order }

    private fun controller(): ActiveProfileController = ActiveProfileController(style, units, routing, alerts, widgets)

    private fun seed(
        profile: ProfileId,
        choice: MapStyleChoice,
        system: UnitSystem,
        hills: RoutePreference,
        signal: Boolean,
    ) {
        style.select(profile, choice)
        units.set(profile, system)
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
