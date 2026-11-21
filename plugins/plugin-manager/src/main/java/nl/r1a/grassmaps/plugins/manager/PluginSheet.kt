package nl.r1a.grassmaps.plugins.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.manifest.LocationNeed

/**
 * The sheet of one plugin: what it is, what it adds, what it needs, and the
 * button that switches it. A stopped plugin offers Restart, which turns it on
 * again and so clears its failures.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun PluginSheet(
    row: PluginRow,
    onSetEnabled: (PluginId, Boolean) -> Unit,
    onDone: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Scrim(onClick = onDone)
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = SHEET_CORNER_DP.dp, topEnd = SHEET_CORNER_DP.dp))
                    .background(CARD)
                    .navigationBarsPadding()
                    .padding(SCREEN_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        ) {
            SheetTitle(row)
            BasicText(text = row.description, style = TextStyle(color = INK, fontSize = BODY_SP.sp))
            if (row.health == PluginHealth.STOPPED) {
                StoppedBox(row.status.failures)
            } else {
                SheetFacts(row)
            }
            SheetButtons(row, onSetEnabled, onDone)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SheetTitle(row: PluginRow) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = row.name,
                style = TextStyle(color = INK, fontSize = TITLE_SP.sp, fontWeight = FontWeight.Bold),
            )
            BasicText(
                text =
                    stringResource(
                        R.string.plugin_manager_version,
                        row.status.info
                            ?.version
                            .orEmpty(),
                    ),
                style = TextStyle(color = MUTED, fontSize = BODY_SP.sp),
            )
        }
        BasicText(
            text = stringResource(healthLabel(row.health)),
            style =
                TextStyle(
                    color = if (row.health == PluginHealth.STOPPED) DANGER else ACCENT,
                    fontSize = CAPTION_SP.sp,
                    fontWeight = FontWeight.Bold,
                ),
        )
    }
}

private fun healthLabel(health: PluginHealth): Int =
    when (health) {
        PluginHealth.OFF -> R.string.plugin_manager_state_off
        PluginHealth.ON -> R.string.plugin_manager_state_on
        PluginHealth.RUNNING -> R.string.plugin_manager_state_running
        PluginHealth.STOPPED -> R.string.plugin_manager_state_stopped
    }

@Suppress("ktlint:standard:function-naming")
@Composable
private fun StoppedBox(failures: Int) {
    BasicText(
        text = stoppedText(failures),
        style = TextStyle(color = DANGER, fontSize = BODY_SP.sp, fontWeight = FontWeight.Medium),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ROW_PADDING_DP.dp))
                .background(DANGER_TINT)
                .padding(ROW_PADDING_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SheetFacts(row: PluginRow) {
    val info = row.status.info ?: return
    Column(verticalArrangement = Arrangement.spacedBy(SPACED_DP.dp)) {
        FactHeading(R.string.plugin_manager_adds)
        Fact(stringResource(R.string.plugin_manager_commands), info.commandCount.toString())
        FactHeading(R.string.plugin_manager_uses)
        Fact(stringResource(R.string.plugin_manager_network), stringResource(networkLabel(row.usesNetwork)))
        Fact(stringResource(R.string.plugin_manager_location), stringResource(locationLabel(info.location)))
        Fact(
            stringResource(R.string.plugin_manager_map_data),
            info.regions.joinToString().ifEmpty { stringResource(R.string.plugin_manager_none) },
        )
        BasicText(
            text = stringResource(R.string.plugin_manager_goes_away),
            style = TextStyle(color = MUTED, fontSize = CAPTION_SP.sp),
        )
    }
}

private fun networkLabel(used: Boolean): Int = if (used) R.string.plugin_manager_needed else R.string.plugin_manager_none

private fun locationLabel(need: LocationNeed): Int =
    when (need) {
        LocationNeed.NONE -> R.string.plugin_manager_none
        LocationNeed.WHILE_IN_USE -> R.string.plugin_manager_location_in_use
        LocationNeed.DURING_NAVIGATION -> R.string.plugin_manager_location_navigation
    }

@Suppress("ktlint:standard:function-naming")
@Composable
private fun FactHeading(label: Int) {
    BasicText(
        text = stringResource(label).uppercase(),
        style = TextStyle(color = MUTED, fontSize = CAPTION_SP.sp, fontWeight = FontWeight.Bold),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Fact(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        BasicText(text = label, style = TextStyle(color = INK, fontSize = BODY_SP.sp))
        BasicText(text = value, style = TextStyle(color = MUTED, fontSize = BODY_SP.sp))
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SheetButtons(
    row: PluginRow,
    onSetEnabled: (PluginId, Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val id = row.status.pluginId
    Row(horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp), modifier = Modifier.fillMaxWidth()) {
        when (row.health) {
            PluginHealth.STOPPED -> {
                SheetButton(stringResource(R.string.plugin_manager_restart), ACCENT) { onSetEnabled(id, true) }
            }

            PluginHealth.OFF -> {
                SheetButton(stringResource(R.string.plugin_manager_turn_on), ACCENT) { onSetEnabled(id, true) }
            }

            else -> {
                SheetButton(stringResource(R.string.plugin_manager_turn_off), DANGER) { onSetEnabled(id, false) }
            }
        }
        SheetButton(stringResource(R.string.plugin_manager_done), INK, onClick = onDone)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun RowScope.SheetButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
) {
    BasicText(
        text = text,
        style = TextStyle(color = color, fontSize = NAME_SP.sp, fontWeight = FontWeight.Bold),
        modifier =
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(PILL_CORNER_DP.dp))
                .background(SURFACE)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = ROW_PADDING_DP.dp),
    )
}
