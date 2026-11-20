@file:Suppress("ktlint:standard:function-naming")

package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.AlertsPageChannel
import nl.r1a.grassmap.plugin.ui.api.AlertsPageState
import nl.r1a.grassmap.plugin.ui.api.AlertsPageSwitch
import nl.r1a.grassmap.plugin.ui.api.ShellColors
import nl.r1a.grassmap.plugin.ui.api.shellColors

private const val PAGE_TITLE_SP = 21
private const val PAGE_SUBTITLE_SP = 12.5f
private const val SECTION_TITLE_SP = 12
private const val ROW_TITLE_SP = 14.5f
private const val ROW_DETAIL_SP = 12.5f
private const val CHANNEL_LABEL_SP = 13f
private const val DONE_LABEL_SP = 17

private const val HEADER_HORIZONTAL_DP = 12
private const val CONTENT_HORIZONTAL_DP = 16
private const val ROW_VERTICAL_DP = 8
private const val ICON_CIRCLE_DP = 36
private const val ICON_GLYPH_SP = 18
private const val SWITCH_WIDTH_DP = 46
private const val SWITCH_HEIGHT_DP = 28
private const val SWITCH_BORDER_DP = 2
private const val SWITCH_KNOB_DP = 18
private const val CHANNEL_HEIGHT_DP = 36
private const val CHANNEL_CORNER_DP = 18
private const val CHANNEL_TINT_ALPHA = 0.14f
private const val DONE_HEIGHT_DP = 50
private const val DONE_CORNER_DP = 25
private const val BACK_BUTTON_DP = 40
private const val BACK_GLYPH_SP = 26

private val CHECK_GLYPH = "\u2713"
private val BACK_GLYPH = "\u2039"
private val CHEVRON_GLYPH = "\u203A"

/**
 * The profile alerts page (T-0413): the three sections of switches, the
 * place-kind row that opens its picker, and the three output channels.
 *
 * The page draws what [content] resolves and reports every tap; it never reads
 * the alert host or a settings file, so the app owns persistence. The picker is
 * local to the page: closing it loses nothing, because each tap already reached
 * its callback.
 */
@Composable
fun AlertsScreenHost(
    state: AlertsPageState,
    strings: Strings,
    dark: Boolean,
    onBack: () -> Unit,
    onSwitch: (AlertsPageSwitch, Boolean) -> Unit,
    onChannel: (AlertsPageChannel, Boolean) -> Unit,
    onPlaceKind: (String, Boolean) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = shellColors(dark)
    val content = alertsPageContent(state, strings)
    val picker = alertsPlacePickerContent(state.chosenPlaceKinds, strings)
    var pickerOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxSize().background(colors.surface)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Header(content = content, colors = colors, onBack = onBack)
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = CONTENT_HORIZONTAL_DP.dp),
            ) {
                content.sections.forEach { section ->
                    SectionTitle(section.title, colors)
                    section.rows.forEach { row ->
                        AlertRow(
                            row = row,
                            colors = colors,
                            onSwitch = onSwitch,
                            onPlaces = { pickerOpen = true },
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
            ReachSection(content = content, colors = colors, onChannel = onChannel)
            DoneButton(content.done, colors, onDone)
        }
        if (pickerOpen) {
            PlacePicker(
                content = picker,
                colors = colors,
                onSelect = onPlaceKind,
                onClose = { pickerOpen = false },
            )
        }
    }
}

@Composable
private fun Header(
    content: AlertsPageContent,
    colors: ShellColors,
    onBack: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = HEADER_HORIZONTAL_DP.dp, end = CONTENT_HORIZONTAL_DP.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(BACK_BUTTON_DP.dp)
                    .semantics { contentDescription = content.backDescription }
                    .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = BACK_GLYPH,
                style = TextStyle(color = colors.ink, fontSize = BACK_GLYPH_SP.sp),
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        Column {
            BasicText(
                text = content.title,
                style = TextStyle(color = colors.ink, fontSize = PAGE_TITLE_SP.sp, fontWeight = FontWeight.Bold),
            )
            BasicText(
                text = content.profileSubtitle,
                style = TextStyle(color = colors.muted, fontSize = PAGE_SUBTITLE_SP.sp),
            )
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    colors: ShellColors,
) {
    BasicText(
        text = title.uppercase(),
        style =
            TextStyle(
                color = colors.muted,
                fontSize = SECTION_TITLE_SP.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            ),
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun AlertRow(
    row: AlertsRowContent,
    colors: ShellColors,
    onSwitch: (AlertsPageSwitch, Boolean) -> Unit,
    onPlaces: () -> Unit,
) {
    val switch = row.switch
    val interaction =
        if (switch == null) {
            Modifier.clickable(role = Role.Button) { onPlaces() }
        } else {
            Modifier.toggleable(value = row.on, role = Role.Switch) { on -> onSwitch(switch, on) }
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = row.description }
                .then(interaction)
                .padding(vertical = ROW_VERTICAL_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconCircle(row = row, colors = colors)
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = row.title,
                style = TextStyle(color = colors.ink, fontSize = ROW_TITLE_SP.sp, fontWeight = FontWeight.SemiBold),
            )
            row.detail?.let { detail ->
                BasicText(
                    text = detail,
                    style = TextStyle(color = colors.muted, fontSize = ROW_DETAIL_SP.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (switch != null) {
            Switch(on = row.on, colors = colors)
        } else {
            BasicText(
                text = CHEVRON_GLYPH,
                style = TextStyle(color = colors.faint, fontSize = 22.sp),
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun IconCircle(
    row: AlertsRowContent,
    colors: ShellColors,
) {
    val glyph = row.switch?.glyph() ?: "\u25C9"
    val colour =
        when (row.switch) {
            AlertsPageSwitch.Favourite -> colors.amberIcon
            AlertsPageSwitch.TrackWaypoint -> colors.profiles.hike
            null -> colors.accent
            else -> colors.muted
        }
    Box(
        modifier =
            Modifier
                .size(ICON_CIRCLE_DP.dp)
                .background(colour.copy(alpha = 0.10f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = glyph,
            style = TextStyle(color = colour, fontSize = ICON_GLYPH_SP.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun Switch(
    on: Boolean,
    colors: ShellColors,
) {
    Box(
        modifier =
            Modifier
                .size(SWITCH_WIDTH_DP.dp, SWITCH_HEIGHT_DP.dp)
                .background(if (on) colors.accent else colors.soft, RoundedCornerShape(14.dp))
                .border(
                    SWITCH_BORDER_DP.dp,
                    if (on) colors.accent else colors.controlBorder,
                    RoundedCornerShape(14.dp),
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = SWITCH_BORDER_DP.dp)
                    .size(SWITCH_KNOB_DP.dp)
                    .background(if (on) colors.onAccent else colors.offSwitchKnob, CircleShape),
        )
    }
}

@Composable
private fun ReachSection(
    content: AlertsPageContent,
    colors: ShellColors,
    onChannel: (AlertsPageChannel, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = CONTENT_HORIZONTAL_DP.dp)) {
        SectionTitle(content.reachTitle, colors)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content.channels.forEach { channel ->
                ChannelChip(
                    channel = channel,
                    colors = colors,
                    onClick = { onChannel(channel.channel, !channel.on) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ChannelChip(
    channel: AlertsChannelContent,
    colors: ShellColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = if (channel.on) colors.accent else colors.muted
    val shape = RoundedCornerShape(CHANNEL_CORNER_DP.dp)
    Row(
        modifier =
            modifier
                .height(CHANNEL_HEIGHT_DP.dp)
                .background(if (channel.on) colors.accent.copy(alpha = CHANNEL_TINT_ALPHA) else colors.soft, shape)
                .border(1.dp, if (channel.on) colors.accent else colors.controlBorder, shape)
                .semantics { contentDescription = channel.description }
                .toggleable(value = channel.on, role = Role.Switch, onValueChange = { onClick() })
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        BasicText(
            text = channel.channel.glyph(),
            style = TextStyle(color = ink, fontSize = 14.sp),
            modifier = Modifier.clearAndSetSemantics {},
        )
        BasicText(
            text = " ${channel.label}",
            style = TextStyle(color = ink, fontSize = CHANNEL_LABEL_SP.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DoneButton(
    label: String,
    colors: ShellColors,
    onDone: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = CONTENT_HORIZONTAL_DP.dp, vertical = 10.dp)
                .height(DONE_HEIGHT_DP.dp)
                // Round 8's primary button, not the amber rating accent: the
                // handoff draws Done as `primary_button`, and the amber fill
                // reached only 2.87:1 against the page in light (T-0636).
                .background(colors.accent, RoundedCornerShape(DONE_CORNER_DP.dp))
                .clickable(role = Role.Button, onClick = onDone),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        BasicText(
            text = CHECK_GLYPH,
            style =
                TextStyle(
                    color = colors.onAccent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
            modifier = Modifier.clearAndSetSemantics {},
        )
        BasicText(
            text = "  $label",
            style =
                TextStyle(
                    color = colors.onAccent,
                    fontSize = DONE_LABEL_SP.sp,
                    fontWeight = FontWeight.Bold,
                ),
        )
    }
}

@Composable
private fun PlacePicker(
    content: AlertsPlacePickerContent,
    colors: ShellColors,
    onSelect: (String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .navigationBarsPadding()
                    .padding(horizontal = CONTENT_HORIZONTAL_DP.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicText(
                    text = content.title,
                    style = TextStyle(color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    text = content.close,
                    style = TextStyle(color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clickable(role = Role.Button, onClick = onClose).padding(8.dp),
                )
            }
            content.kinds.forEach { kind ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = kind.description }
                            .toggleable(value = kind.selected, role = Role.Checkbox) { selected ->
                                onSelect(kind.indexKind, selected)
                            }.padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BasicText(
                        text = if (kind.selected) CHECK_GLYPH else "",
                        style =
                            TextStyle(
                                color = colors.accent,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        modifier = Modifier.size(20.dp).clearAndSetSemantics {},
                    )
                    BasicText(
                        text = kind.label,
                        style =
                            TextStyle(
                                color = if (kind.selected) colors.ink else colors.muted,
                                fontSize = 15.sp,
                                fontWeight = if (kind.selected) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertsRowContent.iconSummary(): String = title

private fun AlertsPageSwitch.glyph(): String =
    when (this) {
        AlertsPageSwitch.OffRoute -> "\u21DD"
        AlertsPageSwitch.Signal -> "\u2316"
        AlertsPageSwitch.Reroute -> "\u27F3"
        AlertsPageSwitch.Arrival -> "\u2691"
        AlertsPageSwitch.RailwayCrossing -> "\u2715"
        AlertsPageSwitch.Tunnel -> "\u25E0"
        AlertsPageSwitch.Hazard -> "\u26A0"
        AlertsPageSwitch.Favourite -> "\u2605"
        AlertsPageSwitch.TrackWaypoint -> "\u2691"
    }

private fun AlertsPageChannel.glyph(): String =
    when (this) {
        AlertsPageChannel.Banner -> "\u24D8"
        AlertsPageChannel.Sound -> "\u266B"
        AlertsPageChannel.Vibration -> "\u25AE"
    }
