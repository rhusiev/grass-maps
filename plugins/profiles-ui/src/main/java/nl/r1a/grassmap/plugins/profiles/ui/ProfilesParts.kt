package nl.r1a.grassmap.plugins.profiles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import nl.r1a.grassmap.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmap.plugin.api.model.ProfileSummary
import nl.r1a.grassmap.plugin.api.service.ProfileSettingsGroup

/** The name the screen shows: a user profile's own name, or a built-in's string. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun profileName(profile: ProfileSummary): String =
    profile.name
        ?: when (profile.id) {
            BuiltInProfiles.WALK.value -> stringResource(R.string.profiles_walk)
            BuiltInProfiles.BIKE.value -> stringResource(R.string.profiles_bike)
            BuiltInProfiles.HIKE.value -> stringResource(R.string.profiles_hike)
            else -> profile.id
        }

/** "Built in", or "Copied from X" when the profile has a source that still exists. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun profileSubtitle(
    profile: ProfileSummary,
    all: List<ProfileSummary>,
): String {
    val source = profile.copiedFrom?.let { id -> all.firstOrNull { it.id == id } }
    return when {
        source != null -> stringResource(R.string.profiles_copied_from, profileName(source))
        profile.builtIn -> stringResource(R.string.profiles_built_in)
        else -> ""
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun groupLabel(group: String): String =
    when (group) {
        ProfileSettingsGroup.ROUTING -> stringResource(R.string.profiles_group_routing)
        ProfileSettingsGroup.ALERTS -> stringResource(R.string.profiles_group_alerts)
        else -> group
    }

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val background = if (filled) ACCENT else NEUTRAL_BUTTON
    val color = if (filled) ON_ACCENT else INK
    val alpha = if (enabled) Color.Unspecified else MUTED
    Box(
        modifier =
            modifier
                .height(BUTTON_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(BUTTON_CORNER_DP.dp))
                .background(if (enabled) background else NEUTRAL_BUTTON)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = text, style = BUTTON_STYLE.copy(color = if (enabled) color else alpha))
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun RadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ChoiceRow(label, selected, onSelect, Role.RadioButton) { RadioMark(selected) }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun CheckRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    ChoiceRow(label, checked, onToggle, Role.Checkbox) { CheckMark(checked) }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    role: Role,
    mark: @Composable () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FIELD_CORNER_DP.dp))
                .background(if (selected && role == Role.RadioButton) ACCENT_TINT else Color.Transparent)
                .then(
                    if (role == Role.RadioButton) {
                        Modifier.selectable(selected = selected, role = role, onClick = onClick)
                    } else {
                        Modifier.toggleable(value = selected, role = role) { onClick() }
                    },
                ).padding(ROW_PADDING_DP.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text = label, style = ROW_TITLE_STYLE)
        mark()
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun RadioMark(selected: Boolean) {
    Box(
        modifier =
            Modifier
                .size(RADIO_SIZE_DP.dp)
                .border(RADIO_STROKE_DP.dp, if (selected) ACCENT else RADIO_OFF, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(RADIO_DOT_DP.dp).background(ACCENT, CircleShape))
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun CheckMark(checked: Boolean) {
    Box(
        modifier =
            Modifier
                .size(RADIO_SIZE_DP.dp)
                .clip(RoundedCornerShape(RADIO_STROKE_DP.dp * 2))
                .background(if (checked) ACCENT else Color.Transparent)
                .border(RADIO_STROKE_DP.dp, if (checked) ACCENT else RADIO_OFF, RoundedCornerShape(RADIO_STROKE_DP.dp * 2)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            BasicText(
                text = CHECK_GLYPH,
                style = TextStyle(color = ON_ACCENT),
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/** A tappable list row with a title, an optional second line and a chevron. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun NavigationRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showChevron: Boolean = true,
    titleColor: Color = INK,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = ROW_PADDING_DP.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(text = title, style = ROW_TITLE_STYLE.copy(color = titleColor))
            if (subtitle.isNotEmpty()) {
                BasicText(text = subtitle, style = BODY_STYLE)
            }
        }
        if (showChevron) {
            BasicText(
                text = CHEVRON,
                style = TITLE_STYLE.copy(color = MUTED),
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun Header(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    val back = stringResource(R.string.profiles_back)
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = BACK_GLYPH,
            style = TITLE_STYLE,
            modifier =
                Modifier
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = back }
                    .padding(end = GAP_DP.dp),
        )
        Column {
            BasicText(text = title, style = TITLE_STYLE)
            if (subtitle.isNotEmpty()) {
                BasicText(text = subtitle, style = BODY_STYLE)
            }
        }
    }
}
