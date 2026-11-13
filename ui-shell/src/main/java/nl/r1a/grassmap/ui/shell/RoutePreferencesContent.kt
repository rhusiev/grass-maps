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
private const val PREF_HILLS_KEY = "route_preferences_hills"
private const val PREF_HILLS_DETAIL_KEY = "route_preferences_hills_detail"
private const val PREF_BUSY_ROADS_KEY = "route_preferences_busy_roads"
private const val PREF_BUSY_ROADS_DETAIL_KEY = "route_preferences_busy_roads_detail"
private const val PREF_ROUGH_SURFACES_KEY = "route_preferences_rough_surfaces"
private const val PREF_ROUGH_SURFACES_DETAIL_KEY = "route_preferences_rough_surfaces_detail"
private const val PREF_AVOID_KEY = "route_preferences_avoid"
private const val PREF_NORMAL_KEY = "route_preferences_normal"
private const val PREF_DONT_MIND_KEY = "route_preferences_dont_mind"
private const val PREF_RESET_KEY = "route_preferences_reset"
private const val PREF_APPLY_KEY = "route_preferences_apply"
private const val PREF_PROFILE_WALK_KEY = "route_preferences_profile_walk"
private const val PREF_PROFILE_BIKE_KEY = "route_preferences_profile_bike"
private const val PREF_PROFILE_HIKE_KEY = "route_preferences_profile_hike"
private const val PREF_PROFILE_CAR_KEY = "route_preferences_profile_car"

private const val CHEVRON_OPEN_GLYPH = "\u2304"
private const val CHEVRON_CLOSED_GLYPH = "\u203A"

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
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = TIGHT_GAP_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = strings.resolve(PREF_TITLE_KEY),
            style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = ENTRY_FONT_SP.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f),
        )
        val summary =
            when {
                overridePreferences != null -> {
                    strings.resolve(PREF_CHANGED_KEY)
                }

                profilePreferences.setCount > 0 -> {
                    strings.resolve(PREF_SET_COUNT_KEY, listOf(profilePreferences.setCount.toString()))
                }

                else -> {
                    ""
                }
            }
        if (summary.isNotEmpty()) {
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
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = SECTION_TOP_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
    ) {
        BasicText(
            text = strings.resolve(PREF_TITLE_KEY),
            style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = TITLE_FONT_SP.sp, fontWeight = FontWeight.Bold),
        )
        BasicText(
            text = strings.resolve(PREF_SCOPE_KEY, listOf(profileLabel(profile, strings))),
            style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = DETAIL_FONT_SP.sp),
        )
        PreferenceRow(
            title = strings.resolve(PREF_HILLS_KEY),
            detail = strings.resolve(PREF_HILLS_DETAIL_KEY),
            value = draft.hills,
            profileValue = profilePreferences.hills,
            strings = strings,
            onChange = { value -> draft = draft.copy(hills = value) },
        )
        PreferenceRow(
            title = strings.resolve(PREF_BUSY_ROADS_KEY),
            detail = strings.resolve(PREF_BUSY_ROADS_DETAIL_KEY),
            value = draft.busyRoads,
            profileValue = profilePreferences.busyRoads,
            strings = strings,
            onChange = { value -> draft = draft.copy(busyRoads = value) },
        )
        PreferenceRow(
            title = strings.resolve(PREF_ROUGH_SURFACES_KEY),
            detail = strings.resolve(PREF_ROUGH_SURFACES_DETAIL_KEY),
            value = draft.roughSurfaces,
            profileValue = profilePreferences.roughSurfaces,
            strings = strings,
            onChange = { value -> draft = draft.copy(roughSurfaces = value) },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = TIGHT_GAP_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionChip(strings.resolve(PREF_RESET_KEY), onReset)
            Box(Modifier.weight(1f))
            ActionChip(strings.resolve(PREF_APPLY_KEY), { onApply(draft) }, ROUTE_ACTION_COLOR)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PreferenceRow(
    title: String,
    detail: String,
    value: RoutePreference,
    profileValue: RoutePreference,
    strings: Strings,
    onChange: (RoutePreference) -> Unit,
) {
    val changed = value != profileValue
    Column(verticalArrangement = Arrangement.spacedBy(TIGHT_GAP_DP.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = title,
                style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = LABEL_FONT_SP.sp, fontWeight = FontWeight.Bold),
            )
            if (changed) {
                BasicText(
                    text = strings.resolve(PREF_CHANGED_KEY),
                    style = TextStyle(color = ROUTE_CHANGED_COLOR, fontSize = DETAIL_FONT_SP.sp),
                )
            }
        }
        BasicText(
            text = detail,
            style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = DETAIL_FONT_SP.sp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TIGHT_GAP_DP.dp),
        ) {
            for (preference in RoutePreference.entries) {
                Segment(
                    label = preferenceLabel(preference, strings),
                    selected = preference == value,
                    marked = changed && preference == profileValue,
                    onClick = { onChange(preference) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    marked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = if (marked) "$label \u2022" else label
    BasicText(
        text = text,
        style =
            TextStyle(
                color = if (selected) ROUTE_VALUE_COLOR else ROUTE_STATUS_COLOR,
                fontSize = DETAIL_FONT_SP.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
        modifier =
            modifier
                .background(
                    if (selected) ROUTE_CHIP_SELECTED_COLOR else ROUTE_CHIP_COLOR,
                    RoundedCornerShape(SEGMENT_CORNER_DP.dp),
                ).clickable(onClick = onClick)
                .padding(
                    horizontal = SEGMENT_PADDING_HORIZONTAL_DP.dp,
                    vertical = SEGMENT_PADDING_VERTICAL_DP.dp,
                ),
    )
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

private fun profileLabel(
    profile: RouteProfile,
    strings: Strings,
): String =
    when (profile) {
        RouteProfile.Walk -> strings.resolve(PREF_PROFILE_WALK_KEY)
        RouteProfile.Bike -> strings.resolve(PREF_PROFILE_BIKE_KEY)
        RouteProfile.Hike -> strings.resolve(PREF_PROFILE_HIKE_KEY)
        RouteProfile.Car -> strings.resolve(PREF_PROFILE_CAR_KEY)
    }
