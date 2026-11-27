package nl.r1a.grassmaps.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.FavouriteListsState
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.plugin.api.model.ProfilesState
import nl.r1a.grassmaps.plugin.api.model.TrackLibraryState
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.api.unit.UnitText
import nl.r1a.grassmaps.plugins.favourites.ui.FavouritesUiContract
import nl.r1a.grassmaps.plugins.profiles.ui.ProfilesUiContract
import nl.r1a.grassmaps.plugins.track.library.TrackLibraryContract
import nl.r1a.grassmaps.tracks.distanceMeters
import nl.r1a.grassmaps.ui.shell.SavedSheetFormat
import nl.r1a.grassmaps.ui.shell.SavedSheetInput
import nl.r1a.grassmaps.ui.shell.WidgetsSavedSheet
import nl.r1a.grassmaps.ui.shell.WidgetsSavedSheetActions
import nl.r1a.grassmaps.ui.shell.savedSheetContent
import java.util.Locale

/**
 * The chevron's half sheet with its data: the active profile's name and
 * widget cards, and the Saved tiles built from the saved places, the saved
 * tracks and [origin], the current fix. The cards are read-only; Edit and Add
 * hand editing to the layout sheet, so the widget controller keeps one owner.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun WidgetsSavedSheetHost(
    container: AppContainer,
    units: UnitSystem,
    origin: GeoPoint?,
    hiddenGroups: Set<String>,
    actions: WidgetsSavedSheetActions,
    bar: @Composable () -> Unit,
) {
    val strings = container.strings
    val access = container.streamAccess
    val profile by container.activeProfile.active.collectAsState()
    val profiles by remember(access) { access.subscribe<ProfilesState>(ProfilesUiContract.stateStream) }
        .collectAsState(initial = ProfilesState())
    val places by remember(access) { access.subscribe<FavouriteListsState>(FavouritesUiContract.listsStream) }
        .collectAsState(initial = FavouriteListsState())
    val library by remember(access) { access.subscribe<TrackLibraryState>(TrackLibraryContract.stateStream) }
        .collectAsState(initial = TrackLibraryState())
    val controller = rememberActiveWidgetController(container)
    val widgetState by controller.state.collectAsState()
    val cells = rememberWidgetCells(container, widgetState.layout, isEditing = false, units = units)
    val format =
        remember(strings, units) {
            SavedSheetFormat(strings, ::distanceMeters) { meters -> UnitText.distance(meters, units, strings, Locale.getDefault()) }
        }
    val saved =
        remember(places, library, origin, hiddenGroups, format) {
            savedSheetContent(SavedSheetInput(places, library.tracks, origin, hiddenGroups), format)
        }
    WidgetsSavedSheet(
        profileName = profileDisplayName(profile.profileId, profiles, strings),
        widgets = cells,
        saved = saved,
        actions = actions,
        barHeight = MAP_BOTTOM_BAR_HEIGHT_DP.dp,
        bar = bar,
    )
}

/**
 * The name a person knows the active profile by: a user profile's own name,
 * or the profiles plugin's string for a built-in one.
 */
internal fun profileDisplayName(
    profile: ProfileId,
    profiles: ProfilesState,
    strings: Strings,
): String {
    profiles.profiles
        .firstOrNull { it.id == profile.value }
        ?.name
        ?.let { return it }
    return BUILT_IN_PROFILE_NAME_KEYS[profile]?.let { key -> strings.resolve(key) } ?: profile.value
}

private val BUILT_IN_PROFILE_NAME_KEYS =
    mapOf(
        BuiltInProfiles.WALK to "profiles_walk",
        BuiltInProfiles.BIKE to "profiles_bike",
        BuiltInProfiles.HIKE to "profiles_hike",
    )
