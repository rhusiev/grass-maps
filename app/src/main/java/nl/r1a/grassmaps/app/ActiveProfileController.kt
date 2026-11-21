package nl.r1a.grassmaps.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.r1a.grassmaps.alerts.AlertSettingsStore
import nl.r1a.grassmaps.alerts.ProfileAlertSettings
import nl.r1a.grassmaps.core.services.RoutingOptionsSettings
import nl.r1a.grassmaps.core.services.UnitSettings
import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.provider.RouteOptions
import nl.r1a.grassmaps.plugins.layers.panel.MapStyleChoice
import nl.r1a.grassmaps.ui.shell.WidgetLayout
import nl.r1a.grassmaps.ui.shell.WidgetLayoutStore

/**
 * The one published state of the active profile (T-0603). A switch reads the
 * five stored categories from their per-profile stores, activates the stores
 * that publish an active flow, and emits one snapshot, so no screen can keep a
 * style or unit value from the previous profile.
 *
 * Widgets and routing are already read by profile at their call sites, so they
 * need no activation: including them in [ProfileState] is what makes a switch
 * observable as one change. Style, units and alerts keep their own active flows
 * for the screens that already collect them and are switched here first.
 */
class ActiveProfileController(
    private val style: MapStyleSettings,
    private val units: UnitSettings,
    private val routing: RoutingOptionsSettings,
    private val alerts: AlertSettingsStore,
    private val widgets: WidgetLayoutStore,
    initialProfile: ProfileId = BuiltInProfiles.WALK,
) {
    /** The five per-profile values of the active profile, published together. */
    data class ProfileState(
        val profileId: ProfileId,
        val widgets: WidgetLayout,
        val style: MapStyleChoice,
        val units: UnitSystem,
        val routing: RouteOptions,
        val alerts: ProfileAlertSettings,
    )

    private val state = MutableStateFlow(snapshot(initialProfile))

    /** The active profile's five categories. */
    val active: StateFlow<ProfileState> = state.asStateFlow()

    /** The active profile id, shared by the map, widget grid and alert page. */
    @Synchronized
    fun activeProfileId(): ProfileId = state.value.profileId

    /** Switches every category to [profile] and publishes one new state. */
    @Synchronized
    fun activate(profile: ProfileId) {
        style.activate(profile)
        units.activate(profile)
        alerts.activate(profile)
        state.value = snapshot(profile)
    }

    private fun snapshot(profile: ProfileId): ProfileState =
        ProfileState(
            profileId = profile,
            widgets = widgets.layoutOf(profile),
            style = style.forProfile(profile),
            units = units.forProfile(profile),
            routing = routing.forProfile(profile),
            alerts = alerts.forProfile(profile),
        )
}
