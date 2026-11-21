package nl.r1a.grassmaps.plugins.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun BackButton(onClick: () -> Unit) {
    val close = stringResource(R.string.plugin_manager_close)
    BasicText(
        text = BACK_GLYPH,
        style = TextStyle(color = INK, fontSize = BACK_GLYPH_SP.sp),
        modifier =
            Modifier
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = close }
                .padding(BACK_TOUCH_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun SectionBlock(
    section: PluginSection,
    onOpen: (PluginId) -> Unit,
    onSetEnabled: (PluginId, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SPACED_DP.dp)) {
        BasicText(
            text = stringResource(groupLabel(section.group)).uppercase(),
            style = TextStyle(color = MUTED, fontSize = CAPTION_SP.sp, fontWeight = FontWeight.Bold),
        )
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(CARD_CORNER_DP.dp)).background(CARD),
        ) {
            section.rows.forEach { row ->
                PluginRowView(row, onOpen = { onOpen(row.status.pluginId) }, onSetEnabled = onSetEnabled)
            }
        }
    }
}

private fun groupLabel(group: PluginGroup): Int =
    when (group) {
        PluginGroup.MAP -> R.string.plugin_manager_group_map
        PluginGroup.ROUTING -> R.string.plugin_manager_group_routing
        PluginGroup.PLACES -> R.string.plugin_manager_group_places
        PluginGroup.WIDGETS -> R.string.plugin_manager_group_widgets
    }

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PluginRowView(
    row: PluginRow,
    onOpen: () -> Unit,
    onSetEnabled: (PluginId, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(ROW_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = row.name,
                style = TextStyle(color = INK, fontSize = NAME_SP.sp, fontWeight = FontWeight.Medium),
            )
            BasicText(text = row.description, style = TextStyle(color = MUTED, fontSize = BODY_SP.sp))
            RowNote(row)
        }
        PluginSwitch(
            checked = row.status.enabled,
            label = row.name,
            onCheckedChange = { onSetEnabled(row.status.pluginId, it) },
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun RowNote(row: PluginRow) {
    if (row.health == PluginHealth.STOPPED) {
        BasicText(
            text = stringResource(R.string.plugin_manager_row_stopped, stoppedText(row.status.failures)),
            style = TextStyle(color = DANGER, fontSize = CAPTION_SP.sp),
        )
    } else if (row.usesNetwork) {
        BasicText(
            text = stringResource(R.string.plugin_manager_uses_network),
            style = TextStyle(color = MUTED, fontSize = CAPTION_SP.sp),
        )
    }
}

@Composable
internal fun stoppedText(failures: Int): String =
    if (failures > 0) {
        pluralStringResource(R.plurals.plugin_manager_stopped_after, failures, failures)
    } else {
        stringResource(R.string.plugin_manager_state_stopped)
    }

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun PluginSwitch(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val travel = SWITCH_WIDTH_DP - SWITCH_KNOB_DP - 2 * SWITCH_INSET_DP
    Box(
        modifier =
            Modifier
                .width(SWITCH_WIDTH_DP.dp)
                .height(SWITCH_HEIGHT_DP.dp)
                .clip(CircleShape)
                .background(if (checked) ACCENT else SWITCH_OFF)
                .border(SWITCH_BORDER_DP.dp, if (checked) ACCENT else SWITCH_LINE, CircleShape)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .semantics { contentDescription = label },
    ) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (SWITCH_INSET_DP + if (checked) travel else 0).dp)
                    .size(SWITCH_KNOB_DP.dp)
                    .clip(CircleShape)
                    .background(if (checked) LocalShellColors.current.onAccent else CARD),
        )
    }
}

/**
 * The filter field at the bottom of the manager. The field carries the hint as
 * its label, so a reader never meets an unnamed edit box, and reports how many
 * plugins the filter leaves as its state description: that text changes only
 * when the filter does, so it is spoken once per change instead of per redraw.
 * The drawn hint is decoration, because the label already says it.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun SearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    filterState: String?,
) {
    val hint = stringResource(R.string.plugin_manager_search)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(SCREEN_PADDING_DP.dp)
                .clip(RoundedCornerShape(PILL_CORNER_DP.dp))
                .background(CARD)
                .border(1.dp, FIELD_LINE, RoundedCornerShape(PILL_CORNER_DP.dp))
                .padding(horizontal = CARD_PADDING_DP.dp, vertical = ROW_PADDING_DP.dp),
    ) {
        if (query.isEmpty()) {
            // The field already carries this hint as its label; the drawn copy
            // must not be a second announcement.
            BasicText(
                text = hint,
                style = TextStyle(color = MUTED, fontSize = NAME_SP.sp),
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = INK, fontSize = NAME_SP.sp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = hint
                        if (filterState != null) {
                            stateDescription = filterState
                        }
                    },
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun Scrim(onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SCRIM)
                .pointerInput(Unit) { detectTapGestures { onClick() } },
    )
}
