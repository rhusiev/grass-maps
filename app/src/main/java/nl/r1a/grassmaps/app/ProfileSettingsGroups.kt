package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.alerts.AlertSettingsStore
import nl.r1a.grassmaps.core.services.RoutingOptionsSettings
import nl.r1a.grassmaps.core.services.ScreenSettings
import nl.r1a.grassmaps.core.services.UnitSettings
import nl.r1a.grassmaps.plugin.api.service.ProfileSettingsGroup

/**
 * The per-profile stores a copy, reset or undo action covers (T-0604). Style
 * and units are per-profile stores like routing and alerts, so leaving them out
 * would silently drop a user's map style and units from every profile action.
 */
internal fun profileSettingsGroups(
    style: MapStyleSettings,
    units: UnitSettings,
    routing: RoutingOptionsSettings,
    alerts: AlertSettingsStore,
    screens: ScreenSettings = ScreenSettings(),
): List<ProfileSettingsGroup> = listOf(routing, alerts, style, units, screens)
