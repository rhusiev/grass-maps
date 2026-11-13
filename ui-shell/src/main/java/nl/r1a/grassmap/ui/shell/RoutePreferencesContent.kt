package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.service.Strings

private const val ENTRY_FONT_SP = 15
private const val TITLE_FONT_SP = 16
private const val LABEL_FONT_SP = 14
private const val DETAIL_FONT_SP = 12
private const val GAP_DP = 8
private const val TIGHT_GAP_DP = 4
private const val SEGMENT_CORNER_DP = 8
private const val SEGMENT_PADDING_HORIZONTAL_DP = 6
private const val SEGMENT_PADDING_VERTICAL_DP = 7
private const val SECTION_TOP_PADDING_DP = 4

private const val PREF_TITLE_KEY = "route_preferences_title"
private const val PREF_SET_COUNT_KEY = "route_preferences_set_count"
private const val PREF_CHANGED_KEY = "route_preferences_changed"
private const val PREF_SCOPE_KEY = "route_preferences_scope"
private const val PREF_ENTRY_OPEN_KEY = "route_preferences_open_description"
private const val PREF_ENTRY_CLOSE_KEY = "route_preferences_close_description"
private const val PREF_HILLS_KEY = "route_preferences_hills"
private const val PREF_HILLS_DETAIL_KEY = "route_preferences_hills_detail"
private const val PREF_BUSY_ROADS_KEY = "route_preferences_busy_roads"
private const val PREF_BUSY_ROADS_DETAIL_KEY = "route_preferences_busy_roads_detail"
private const val PREF_ROUGH_SURFACES_KEY = "route_preferences_rough_surfaces"
private const val PREF_ROUGH_SURFACES_DETAIL_KEY = "route_preferences_rough_surfaces_detail"
private const val PREF_AVOID_KEY = "route_preferences_avoid"
private const val PREF_NORMAL_KEY = "route_preferences_normal"
private const val PREF_DONT_MIND_KEY = "route_preferences_dont_mind"
private const val PREF_STEP_KEY = "route_preferences_step_description"
private const val PREF_STEP_DEFAULT_KEY = "route_preferences_step_default_description"
private const val PREF_RESET_KEY = "route_preferences_reset"
private const val PREF_APPLY_KEY = "route_preferences_apply"
private const val PREF_PROFILE_WALK_KEY = "route_preferences_profile_walk"
private const val PREF_PROFILE_BIKE_KEY = "route_preferences_profile_bike"
private const val PREF_PROFILE_HIKE_KEY = "route_preferences_profile_hike"
private const val PREF_PROFILE_CAR_KEY = "route_preferences_profile_car"

private const val CHEVRON_OPEN_GLYPH = "\u2304"
private const val CHEVRON_CLOSED_GLYPH = "\u203A"
private const val DEFAULT_DOT = " \u2022"

/** Which routing option a row edits. */
internal enum class PreferenceAttribute {
    Hills,
    BusyRoads,
    RoughSurfaces,
}

/** The collapsed entry's resolved text and accessibility description. */
internal data class RoutePreferencesEntryContent(
    val title: String,
    val summary: String?,
    val description: String,
)

/** The sheet's resolved text, rows and actions. */
internal data class RoutePreferencesSheetContent(
    val title: String,
    val scope: String,
    val rows: List<RoutePreferenceRowContent>,
    val reset: String,
    val apply: String,
)

/** One attribute row; a changed row marks the profile default with a dot. */
internal data class RoutePreferenceRowContent(
    val attribute: PreferenceAttribute,
    val title: String,
    val detail: String,
    val changed: Boolean,
    val steps: List<RoutePreferenceStepContent>,
)

/** One segment of a row, with the text its accessibility node reads. */
internal data class RoutePreferenceStepContent(
    val preference: RoutePreference,
    val label: String,
    val selected: Boolean,
    val profileDefault: Boolean,
    val description: String,
)

/**
 * The collapsed entry in the route preview. The design's route sheet is opened
 * from the preview, so this row carries the same title and the same changed or
 * set summary (T-0421).
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun RoutePreferencesEntry(
    profilePreferences: RouteOptions,
    overridePreferences: RouteOptions?,
    strings: Strings,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val content = routePreferencesEntryContent(profilePreferences, overridePreferences, expanded, strings)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = content.description }
                .clickable(onClick = onClick)
                .padding(vertical = TIGHT_GAP_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = content.title,
            style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = ENTRY_FONT_SP.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f),
        )
        content.summary?.let { summary ->
            BasicText(
                text = summary,
                style = TextStyle(color = ROUTE_CHANGED_COLOR, fontSize = DETAIL_FONT_SP.sp),
            )
        }
        BasicText(
            text = if (expanded) CHEVRON_OPEN_GLYPH else CHEVRON_CLOSED_GLYPH,
            style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = TITLE_FONT_SP.sp),
        )
    }
}

/**
 * The one-route preferences sheet from the round6 design: three independent
 * steps, the profile default marked with a dot on every changed row, and the
 * Reset and Apply actions. The draft lives here, so a tap changes nothing until
 * Apply; Reset goes straight back to the profile (T-0421).
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun RoutePreferencesContent(
    profile: RouteProfile,
    profilePreferences: RouteOptions,
    overridePreferences: RouteOptions?,
    strings: Strings,
    onApply: (RouteOptions) -> Unit,
    onReset: () -> Unit,
) {
    var draft by remember(profilePreferences, overridePreferences) {
        mutableStateOf(overridePreferences ?: profilePreferences)
    }
    val content = routePreferencesSheetContent(profile, profilePreferences, draft, strings)
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = SECTION_TOP_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
    ) {
        BasicText(
            text = content.title,
            style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = TITLE_FONT_SP.sp, fontWeight = FontWeight.Bold),
        )
        BasicText(
            text = content.scope,
            style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = DETAIL_FONT_SP.sp),
        )
        for (row in content.rows) {
            PreferenceRow(
                row = row,
                strings = strings,
                onChange = { preference -> draft = draft.with(row.attribute, preference) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = TIGHT_GAP_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionChip(content.reset, onReset)
            Box(Modifier.weight(1f))
            ActionChip(content.apply, { onApply(draft) }, ROUTE_ACTION_COLOR)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PreferenceRow(
    row: RoutePreferenceRowContent,
    strings: Strings,
    onChange: (RoutePreference) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TIGHT_GAP_DP.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = row.title,
                style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = LABEL_FONT_SP.sp, fontWeight = FontWeight.Bold),
            )
            if (row.changed) {
                BasicText(
                    text = strings.resolve(PREF_CHANGED_KEY),
                    style = TextStyle(color = ROUTE_CHANGED_COLOR, fontSize = DETAIL_FONT_SP.sp),
                )
            }
        }
        BasicText(
            text = row.detail,
            style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = DETAIL_FONT_SP.sp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TIGHT_GAP_DP.dp),
        ) {
            for (step in row.steps) {
                Segment(
                    step = step,
                    onClick = { onChange(step.preference) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Segment(
    step: RoutePreferenceStepContent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = if (step.profileDefault) step.label + DEFAULT_DOT else step.label,
        style =
            TextStyle(
                color = if (step.selected) ROUTE_VALUE_COLOR else ROUTE_STATUS_COLOR,
                fontSize = DETAIL_FONT_SP.sp,
                fontWeight = if (step.selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
        modifier =
            modifier
                .semantics { contentDescription = step.description }
                .background(
                    if (step.selected) ROUTE_CHIP_SELECTED_COLOR else ROUTE_CHIP_COLOR,
                    RoundedCornerShape(SEGMENT_CORNER_DP.dp),
                ).clickable(onClick = onClick)
                .padding(
                    horizontal = SEGMENT_PADDING_HORIZONTAL_DP.dp,
                    vertical = SEGMENT_PADDING_VERTICAL_DP.dp,
                ),
    )
}

/** Resolves the collapsed entry; a route-level change wins over the set count. */
internal fun routePreferencesEntryContent(
    profilePreferences: RouteOptions,
    overridePreferences: RouteOptions?,
    expanded: Boolean,
    strings: Strings,
): RoutePreferencesEntryContent {
    val effective = overridePreferences ?: profilePreferences
    val summary =
        when {
            effective != profilePreferences -> {
                strings.resolve(PREF_CHANGED_KEY)
            }

            profilePreferences.setCount > 0 -> {
                strings.resolve(PREF_SET_COUNT_KEY, listOf(profilePreferences.setCount.toString()))
            }

            else -> {
                null
            }
        }
    return RoutePreferencesEntryContent(
        title = strings.resolve(PREF_TITLE_KEY),
        summary = summary,
        description = strings.resolve(if (expanded) PREF_ENTRY_CLOSE_KEY else PREF_ENTRY_OPEN_KEY),
    )
}

/** Resolves the sheet for [draft], the values a tap would apply. */
internal fun routePreferencesSheetContent(
    profile: RouteProfile,
    profilePreferences: RouteOptions,
    draft: RouteOptions,
    strings: Strings,
): RoutePreferencesSheetContent =
    RoutePreferencesSheetContent(
        title = strings.resolve(PREF_TITLE_KEY),
        scope = strings.resolve(PREF_SCOPE_KEY, listOf(profileLabel(profile, strings))),
        rows =
            listOf(
                row(PreferenceAttribute.Hills, PREF_HILLS_KEY, PREF_HILLS_DETAIL_KEY, draft.hills, profilePreferences.hills, strings),
                row(
                    PreferenceAttribute.BusyRoads,
                    PREF_BUSY_ROADS_KEY,
                    PREF_BUSY_ROADS_DETAIL_KEY,
                    draft.busyRoads,
                    profilePreferences.busyRoads,
                    strings,
                ),
                row(
                    PreferenceAttribute.RoughSurfaces,
                    PREF_ROUGH_SURFACES_KEY,
                    PREF_ROUGH_SURFACES_DETAIL_KEY,
                    draft.roughSurfaces,
                    profilePreferences.roughSurfaces,
                    strings,
                ),
            ),
        reset = strings.resolve(PREF_RESET_KEY),
        apply = strings.resolve(PREF_APPLY_KEY),
    )

private fun row(
    attribute: PreferenceAttribute,
    titleKey: String,
    detailKey: String,
    value: RoutePreference,
    profileValue: RoutePreference,
    strings: Strings,
): RoutePreferenceRowContent {
    val title = strings.resolve(titleKey)
    return RoutePreferenceRowContent(
        attribute = attribute,
        title = title,
        detail = strings.resolve(detailKey),
        changed = value != profileValue,
        steps =
            RoutePreference.entries.map { preference ->
                val label = preferenceLabel(preference, strings)
                RoutePreferenceStepContent(
                    preference = preference,
                    label = label,
                    selected = preference == value,
                    profileDefault = preference == profileValue,
                    description =
                        strings.resolve(
                            if (preference == profileValue) PREF_STEP_DEFAULT_KEY else PREF_STEP_KEY,
                            listOf(title, label),
                        ),
                )
            },
    )
}

private fun RouteOptions.with(
    attribute: PreferenceAttribute,
    preference: RoutePreference,
): RouteOptions =
    when (attribute) {
        PreferenceAttribute.Hills -> copy(hills = preference)
        PreferenceAttribute.BusyRoads -> copy(busyRoads = preference)
        PreferenceAttribute.RoughSurfaces -> copy(roughSurfaces = preference)
    }

private fun preferenceLabel(
    preference: RoutePreference,
    strings: Strings,
): String =
    when (preference) {
        RoutePreference.Avoid -> strings.resolve(PREF_AVOID_KEY)
        RoutePreference.Normal -> strings.resolve(PREF_NORMAL_KEY)
        RoutePreference.DontMind -> strings.resolve(PREF_DONT_MIND_KEY)
    }

internal fun profileLabel(
    profile: RouteProfile,
    strings: Strings,
): String =
    when (profile) {
        RouteProfile.Walk -> strings.resolve(PREF_PROFILE_WALK_KEY)
        RouteProfile.Bike -> strings.resolve(PREF_PROFILE_BIKE_KEY)
        RouteProfile.Hike -> strings.resolve(PREF_PROFILE_HIKE_KEY)
        RouteProfile.Car -> strings.resolve(PREF_PROFILE_CAR_KEY)
    }
