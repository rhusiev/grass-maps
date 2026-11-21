package nl.r1a.grassmaps.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.ui.api.inkOn
import nl.r1a.grassmaps.plugin.ui.api.shellColors

private const val TITLE_SP = 21
private const val SUBTITLE_SP = 12.5f
private const val SECTION_SP = 12
private const val ROW_TITLE_SP = 14.5f
private const val DETAIL_SP = 12f
private const val CHIP_SP = 12.5f
private const val BACK_SP = 26

private const val HORIZONTAL_DP = 16
private const val ROW_VERTICAL_DP = 10
private const val SECTION_TOP_DP = 18
private const val BACK_DP = 40
private const val CHIP_HEIGHT_DP = 36
private const val SWITCH_WIDTH_DP = 46
private const val SWITCH_HEIGHT_DP = 28
private const val SWITCH_KNOB_DP = 20
private const val SWITCH_PADDING_DP = 4
private const val CHIP_CORNER_DP = 18
private val BACK_GLYPH = "\u2039"

/**
 * The T-0111 settings screen. Round 9 tokens only. The read-only display
 * summary stays at the top; the six controls sit in the lower thumb-reach
 * half. Each row is a plain press target with no separator line.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun ScreenSettingsHost(
    content: ScreenSettingsContent,
    dark: Boolean,
    onBack: () -> Unit,
    onChoice: (String, Int) -> Unit,
    onSwitch: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = shellColors(dark)
    Box(modifier = modifier.fillMaxSize().background(colors.surface)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = HORIZONTAL_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = BACK_GLYPH,
                    style = TextStyle(color = colors.accent, fontSize = BACK_SP.sp),
                    modifier =
                        Modifier
                            .size(BACK_DP.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onBack),
                )
                BasicText(text = content.title, style = TextStyle(color = colors.ink, fontSize = TITLE_SP.sp, fontWeight = FontWeight.Bold))
            }
            ReadOnlySummary(content = content, colors = colors)
            Spacer(Modifier.weight(1f))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(2f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = HORIZONTAL_DP.dp),
            ) {
                content.rows.forEach { row ->
                    when (row.kind) {
                        ScreenRowKind.CHOICE -> ChoiceRow(row, colors, onChoice)
                        ScreenRowKind.SWITCH -> SwitchRow(row, colors, onSwitch)
                    }
                }
                Spacer(Modifier.height(ROW_VERTICAL_DP.dp))
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ReadOnlySummary(
    content: ScreenSettingsContent,
    colors: nl.r1a.grassmaps.plugin.ui.api.ShellColors,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = HORIZONTAL_DP.dp)) {
        BasicText(text = content.subtitle, style = TextStyle(color = colors.muted, fontSize = SUBTITLE_SP.sp))
        BasicText(
            text = content.readOnlyTitle,
            style = TextStyle(color = colors.muted, fontSize = SECTION_SP.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(top = SECTION_TOP_DP.dp),
        )
        BasicText(
            text = content.summary,
            style = TextStyle(color = colors.ink, fontSize = ROW_TITLE_SP.sp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(CHIP_CORNER_DP.dp))
                    .background(colors.soft)
                    .padding(horizontal = HORIZONTAL_DP.dp, vertical = ROW_VERTICAL_DP.dp),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ChoiceRow(
    row: ScreenSettingsRow,
    colors: nl.r1a.grassmaps.plugin.ui.api.ShellColors,
    onChoice: (String, Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = ROW_VERTICAL_DP.dp)) {
        BasicText(text = row.title, style = TextStyle(color = colors.ink, fontSize = ROW_TITLE_SP.sp))
        BasicText(text = row.detail, style = TextStyle(color = colors.muted, fontSize = DETAIL_SP.sp))
        Spacer(Modifier.height(ROW_VERTICAL_DP.dp / 2))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.options.forEachIndexed { index, option ->
                val selected = index == row.selected
                BasicText(
                    text = option.label,
                    style =
                        TextStyle(
                            color = if (selected) inkOn(colors.selectedChip) else colors.ink,
                            fontSize = CHIP_SP.sp,
                        ),
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(CHIP_HEIGHT_DP.dp)
                            .clip(RoundedCornerShape(CHIP_CORNER_DP.dp))
                            .background(if (selected) colors.selectedChip else colors.soft)
                            .clickable(role = Role.RadioButton, onClick = { onChoice(row.key, index) })
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SwitchRow(
    row: ScreenSettingsRow,
    colors: nl.r1a.grassmaps.plugin.ui.api.ShellColors,
    onSwitch: (String, Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Switch, onClick = { onSwitch(row.key, !row.checked) })
                .padding(vertical = ROW_VERTICAL_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(text = row.title, style = TextStyle(color = colors.ink, fontSize = ROW_TITLE_SP.sp))
            BasicText(text = row.detail, style = TextStyle(color = colors.muted, fontSize = DETAIL_SP.sp))
        }
        Spacer(Modifier.width(HORIZONTAL_DP.dp))
        Switch(checked = row.checked, colors = colors)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Switch(
    checked: Boolean,
    colors: nl.r1a.grassmaps.plugin.ui.api.ShellColors,
) {
    Box(
        modifier =
            Modifier
                .size(SWITCH_WIDTH_DP.dp, SWITCH_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(SWITCH_HEIGHT_DP.dp / 2))
                .background(if (checked) colors.accent else colors.controlBorder),
    ) {
        Box(
            modifier =
                Modifier
                    .padding(SWITCH_PADDING_DP.dp)
                    .size(SWITCH_KNOB_DP.dp)
                    .clip(CircleShape)
                    .background(if (checked) colors.onAccent else colors.offSwitchKnob)
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart),
        )
    }
}
