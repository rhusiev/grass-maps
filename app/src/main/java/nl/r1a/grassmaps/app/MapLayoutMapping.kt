package nl.r1a.grassmaps.app

import android.content.Context
import kotlinx.serialization.json.JsonObject
import nl.r1a.grassmaps.core.services.MapButtonLayout
import nl.r1a.grassmaps.core.services.MapControlCommands
import nl.r1a.grassmaps.plugin.api.contract.CommandId
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugins.map.controls.CameraMode
import nl.r1a.grassmaps.ui.shell.ControlBarLabels
import nl.r1a.grassmaps.ui.shell.ControlButton
import nl.r1a.grassmaps.ui.shell.GridWidget
import nl.r1a.grassmaps.ui.shell.MapLayoutSheetContent
import nl.r1a.grassmaps.ui.shell.MapLayoutSheetLabels
import nl.r1a.grassmaps.ui.shell.MapLayoutStrings
import nl.r1a.grassmaps.ui.shell.WidgetLayout
import nl.r1a.grassmaps.ui.shell.mapLayoutSheetContent

/** What a map bar button does when it is pressed (T-0606). */
internal enum class MapButtonKind {
    Command,
    Saved,
    Tracks,
}

/** The map screen's action for a configurable control id. */
internal fun mapButtonKind(id: CommandId): MapButtonKind =
    when (id) {
        MapControlCommands.SAVED -> MapButtonKind.Saved
        MapControlCommands.TRACKS -> MapButtonKind.Tracks
        else -> MapButtonKind.Command
    }

/**
 * Runs one map button. A saved-lists or track-library control opens its
 * screen; every other control goes to the command bus with [parameters], which
 * is how a quick-access binding keeps its stored arguments.
 */
internal fun dispatchLayoutButton(
    id: CommandId,
    parameters: JsonObject,
    invokeCommand: (CommandId, JsonObject) -> Unit,
    openSaved: () -> Unit,
    openTracks: () -> Unit,
) {
    when (mapButtonKind(id)) {
        MapButtonKind.Saved -> openSaved()
        MapButtonKind.Tracks -> openTracks()
        MapButtonKind.Command -> invokeCommand(id, parameters)
    }
}

/**
 * The bar's buttons in slot order, with null for a free slot. A command the
 * app does not draw leaves its slot empty instead of moving another button.
 */
internal fun layoutControlButtons(
    slots: List<CommandId?>,
    context: Context,
    mode: CameraMode,
): List<ControlButton?> = slots.map { id -> id?.let { controlButton(it, context, mode) } }

/** The two tabs of the layout sheet, built from the app's catalogs. */
internal fun layoutSheetContent(
    layout: MapButtonLayout,
    widgetLayout: WidgetLayout,
    strings: Strings,
): MapLayoutSheetContent =
    mapLayoutSheetContent(
        buttonKeys = MapControlCommands.CONFIGURABLE.map { it.value },
        buttonTitle = { key -> layoutButtonTitle(CommandId(key), strings) },
        placedButtons = layout.commands.map { it.value }.toSet(),
        widgetKeys = GridWidget.entries.map { it.storageKey },
        widgetTitle = { key -> GridWidget.fromStorageKey(key)?.let { widget -> widgetTitle(widget, strings) } ?: key },
        placedWidgets = widgetLayout.widgets.map { it.storageKey }.toSet(),
    )

/** The title of one configurable button in the sheet. */
internal fun layoutButtonTitle(
    id: CommandId,
    strings: Strings,
): String =
    strings.resolve(
        when (id) {
            MapControlCommands.ZOOM_IN -> MapLayoutStrings.BUTTON_ZOOM_IN
            MapControlCommands.ZOOM_OUT -> MapLayoutStrings.BUTTON_ZOOM_OUT
            MapControlCommands.NORTH_UP -> MapLayoutStrings.BUTTON_COMPASS
            MapControlCommands.FOLLOW -> MapLayoutStrings.BUTTON_FOLLOW
            MapControlCommands.ROUTE_PLANNER -> MapLayoutStrings.BUTTON_ROUTE_PLANNER
            MapControlCommands.SAVED -> MapLayoutStrings.BUTTON_SAVED
            MapControlCommands.TRACKS -> MapLayoutStrings.BUTTON_TRACKS
            else -> return id.value
        },
    )

/** The quick-access row's assigned title, or null when the button runs nothing. */
internal fun layoutQuickAccessTitle(
    layout: MapButtonLayout,
    strings: Strings,
): String? = layout.quickAccess?.let { binding -> layoutButtonTitle(binding.command, strings) }

/** The resolved text of the layout sheet. */
internal fun layoutSheetLabels(strings: Strings): MapLayoutSheetLabels =
    MapLayoutSheetLabels(
        buttonsTab = strings.resolve(MapLayoutStrings.BUTTONS_TAB),
        widgetsTab = strings.resolve(MapLayoutStrings.WIDGETS_TAB),
        close = strings.resolve(MapLayoutStrings.CLOSE),
        hintButtons = strings.resolve(MapLayoutStrings.HINT_BUTTONS),
        hintWidgets = strings.resolve(MapLayoutStrings.HINT_WIDGETS),
        notShown = strings.resolve(MapLayoutStrings.NOT_SHOWN),
        onScreen = strings.resolve(MapLayoutStrings.ON_SCREEN),
        onScreenNote = strings.resolve(MapLayoutStrings.ON_SCREEN_NOTE),
        quickAccess = strings.resolve(MapLayoutStrings.QUICK_ACCESS),
        none = strings.resolve(MapLayoutStrings.NONE),
        quickAccessDescription = strings.resolve(MapLayoutStrings.QUICK_ACCESS_DESCRIPTION),
        arc = strings.resolve(MapLayoutStrings.ARC),
        arcDescription = strings.resolve(MapLayoutStrings.ARC_DESCRIPTION),
        placeDescription = { title -> strings.resolve(MapLayoutStrings.PLACE_DESCRIPTION, listOf(title)) },
        removeDescription = { title -> strings.resolve(MapLayoutStrings.REMOVE_DESCRIPTION, listOf(title)) },
    )
