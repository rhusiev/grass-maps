@file:Suppress("ktlint:standard:function-naming")

package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.NavigationAlertGlyph
import nl.r1a.grassmap.plugin.ui.api.NavigationAlertSwitches
import nl.r1a.grassmap.plugin.ui.api.NavigationAlertTone
import nl.r1a.grassmap.plugin.ui.api.NavigationCameraSwitches
import nl.r1a.grassmap.plugin.ui.api.NavigationReadout
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenAlert
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenColors
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenState
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenStatus
import nl.r1a.grassmap.plugin.ui.api.NavigationTripSummary

private const val SCREEN_EDGE_DP = 10
private const val BAR_EDGE_DP = 12
private const val BAR_HEIGHT_DP = 64
private const val BAR_BOTTOM_DP = 12
private const val BAR_CORNER_DP = 32
private const val CHIP_CORNER_DP = 18
private const val CHIP_HEIGHT_DP = 36
private const val CHIP_PADDING_HORIZONTAL_DP = 11
private const val END_HEIGHT_DP = 40
private const val END_CORNER_DP = 20
private const val DONE_HEIGHT_DP = 52
private const val ROUND_BUTTON_DP = 40
private const val RECENTRE_DP = 52
private const val SHEET_CORNER_DP = 22
private const val SHEET_PADDING_DP = 16
private const val SHEET_HANDLE_WIDTH_DP = 36
private const val SHEET_HANDLE_HEIGHT_DP = 4
private const val SPEED_TILE_CORNER_DP = 14
private const val SWITCH_WIDTH_DP = 44
private const val SWITCH_HEIGHT_DP = 26
private const val SWITCH_KNOB_DP = 20
private const val BANNER_CORNER_DP = 16
private const val DASH = "–"

private data class NavigationPalette(
    val ink: Color,
    val muted: Color,
    val soft: Color,
    val surface: Color,
    val glass: Color,
    val accent: Color,
    val danger: Color,
    val dangerSoft: Color,
    val dark: Boolean,
)

private fun palette(dark: Boolean): NavigationPalette =
    if (dark) {
        NavigationPalette(
            ink = Color(0xFFE8EAED),
            muted = Color(0xFF9AA0A6),
            soft = Color(0xFF2F343A),
            surface = Color(0xFF24282D),
            glass = Color(0xEB24282D),
            accent = Color(0xFF5FD39A),
            danger = Color(0xFFF28B82),
            dangerSoft = Color(0xFF4A2524),
            dark = true,
        )
    } else {
        NavigationPalette(
            ink = Color(0xFF202124),
            muted = Color(0xFF686C72),
            soft = Color(0xFFF2F1EE),
            surface = Color.White,
            glass = Color(0xE6FFFFFF),
            accent = Color(NavigationScreenColors.GREEN),
            danger = Color(NavigationScreenColors.DANGER),
            dangerSoft = Color(NavigationScreenColors.DANGER_SOFT),
            dark = false,
        )
    }

/**
 * The navigation screen (T-0320): the turn card at the top, the next-stop and
 * speed readouts, the alert banners, the End/ETA/expand bar and the expanded
 * trip, camera and alert sheets.
 *
 * The host draws a [state] it is given and never reads the follower, the clock,
 * the camera or an alert stream itself. The app derives the state once per
 * published navigation state and owns every action. The turn card is a slot so
 * `plugins/guidance-ui` keeps the read-only card it owns; the shell keeps the
 * bar, the sheet and the controls.
 *
 * [sheet] is controlled: the host reports a change through [onSheetChange] and
 * draws the sheet it is given, so a test can reach every control without a
 * gesture. [dark] selects the round 5 dark tokens; the app passes the system
 * theme.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun NavigationScreenHost(
    state: NavigationScreenState,
    strings: Strings,
    sheet: NavigationSheet,
    dark: Boolean,
    turnCard: @Composable (Modifier) -> Unit,
    onSheetChange: (NavigationSheet) -> Unit,
    onEnd: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDone: () -> Unit,
    onRecentre: () -> Unit,
    onCameraAutoZoom: (Boolean) -> Unit,
    onCameraAutoCentre: (Boolean) -> Unit,
    onCameraTilt: (Boolean) -> Unit,
    onAlertSwitch: (NavigationAlertSwitch, Boolean) -> Unit,
    onResetToProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = palette(dark)
    val controls = navigationControls(state, sheet)
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = SCREEN_EDGE_DP.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            turnCard(Modifier.fillMaxWidth())
            if (state.status != NavigationScreenStatus.Arrived) {
                ReadoutRow(state.readout, strings, colors)
                AlertBanners(state.alerts.take(MAX_BANNERS), colors)
            }
        }
        if (NavigationControl.Recentre in controls && sheet == NavigationSheet.Collapsed) {
            RecentreButton(
                buttonLabel = strings.resolve(NavigationScreenStrings.RECENTER),
                colors = colors,
                onClick = onRecentre,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 14.dp, bottom = (BAR_HEIGHT_DP + BAR_BOTTOM_DP * 2 + 12).dp),
            )
        }
        if (sheet == NavigationSheet.Collapsed || state.status == NavigationScreenStatus.Arrived) {
            Bar(
                state = state,
                strings = strings,
                colors = colors,
                controls = controls,
                onEnd = onEnd,
                onResume = onResume,
                onDone = onDone,
                onExpand = { onSheetChange(NavigationSheet.Trip) },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = BAR_EDGE_DP.dp, vertical = BAR_BOTTOM_DP.dp),
            )
        } else if (sheet == NavigationSheet.Trip) {
            TripSheet(
                state = state,
                strings = strings,
                colors = colors,
                controls = controls,
                onEnd = onEnd,
                onPause = onPause,
                onResume = onResume,
                onCollapse = { onSheetChange(NavigationSheet.Collapsed) },
                onCameraAndAlerts = { onSheetChange(NavigationSheet.Settings) },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
            )
        } else {
            SettingsSheet(
                state = state,
                strings = strings,
                colors = colors,
                controls = controls,
                onBack = { onSheetChange(NavigationSheet.Trip) },
                onReset = onResetToProfile,
                onCameraAutoZoom = onCameraAutoZoom,
                onCameraAutoCentre = onCameraAutoCentre,
                onCameraTilt = onCameraTilt,
                onAlertSwitch = onAlertSwitch,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun ReadoutRow(
    readout: NavigationReadout,
    strings: Strings,
    colors: NavigationPalette,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (readout.stopDistance != null || readout.stopName != null) {
            val stop = listOfNotNull(readout.stopDistance, readout.stopTime).joinToString(" · ")
            Row(
                modifier = glassChip(colors),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Glyph("\u25C6", 14, colors.accent)
                BasicText(
                    text = stop,
                    style = TextStyle(color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
                readout.stopName?.let { name ->
                    BasicText(text = name, style = TextStyle(color = colors.muted, fontSize = 12.sp))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = glassChip(colors),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Glyph("\u25D4", 14, colors.muted)
            BasicText(
                text = readout.speedKmh ?: DASH,
                style = TextStyle(color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
            BasicText(
                text = strings.resolve(NavigationScreenStrings.SPEED_UNIT),
                style = TextStyle(color = colors.muted, fontSize = 12.sp),
            )
        }
    }
}

@Composable
private fun AlertBanners(
    alerts: List<NavigationScreenAlert>,
    colors: NavigationPalette,
) {
    if (alerts.isEmpty()) {
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Banner(alerts.first(), slim = false, colors = colors)
        if (alerts.size > 1) {
            Banner(alerts[1], slim = true, colors = colors)
        }
    }
}

@Composable
private fun Banner(
    alert: NavigationScreenAlert,
    slim: Boolean,
    colors: NavigationPalette,
) {
    val warning = alert.tone == NavigationAlertTone.Warning
    val background = Color(if (warning) NavigationScreenColors.WARNING_BG else NavigationScreenColors.INFO_BG)
    val line = Color(if (warning) NavigationScreenColors.WARNING_LINE else NavigationScreenColors.INFO_LINE)
    val ink = Color(if (warning) NavigationScreenColors.WARNING_INK else NavigationScreenColors.INFO_INK)
    val sub = Color(if (warning) NavigationScreenColors.WARNING_SUB else NavigationScreenColors.INFO_SUB)
    if (slim) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(background, RoundedCornerShape(BANNER_CORNER_DP.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Glyph(alertGlyph(alert.glyph), 15, ink)
            BasicText(
                text = alert.title,
                style = TextStyle(color = ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
            )
            alert.detail?.let { detail ->
                Spacer(Modifier.weight(1f))
                BasicText(text = detail, style = TextStyle(color = sub, fontSize = 12.5.sp))
            }
        }
        return
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background, RoundedCornerShape(BANNER_CORNER_DP.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(34.dp)
                    .background(line.copy(alpha = 0.2f), RoundedCornerShape(17.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(alertGlyph(alert.glyph), 18, ink)
        }
        Column {
            BasicText(
                text = alert.title,
                style = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
            alert.detail?.let { detail ->
                BasicText(text = detail, style = TextStyle(color = sub, fontSize = 12.5.sp))
            }
        }
    }
}

@Composable
private fun Bar(
    state: NavigationScreenState,
    strings: Strings,
    colors: NavigationPalette,
    controls: Set<NavigationControl>,
    onEnd: () -> Unit,
    onResume: () -> Unit,
    onDone: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT_DP.dp)
                .border(1.dp, colors.muted.copy(alpha = 0.18f), RoundedCornerShape(BAR_CORNER_DP.dp))
                .background(colors.surface, RoundedCornerShape(BAR_CORNER_DP.dp))
                .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (NavigationControl.Done in controls) {
            PrimaryPill(
                label = strings.resolve(NavigationScreenStrings.DONE),
                glyph = "\u2713",
                background = colors.accent,
                ink = Color.White,
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(DONE_HEIGHT_DP.dp),
            )
            return@Box
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (NavigationControl.End in controls) {
                EndPill(
                    label = strings.resolve(NavigationScreenStrings.END),
                    colors = colors,
                    onClick = onEnd,
                )
            }
            if (NavigationControl.Resume in controls) {
                PrimaryPill(
                    label = strings.resolve(NavigationScreenStrings.RESUME),
                    glyph = "\u25B6",
                    background = colors.accent,
                    ink = Color.White,
                    onClick = onResume,
                    modifier = Modifier.weight(1f).height(END_HEIGHT_DP.dp),
                )
            } else {
                Eta(state, colors, Modifier.weight(1f))
            }
            RoundButton(
                glyph = "\u2303",
                description = strings.resolve(NavigationScreenStrings.EXPAND),
                colors = colors,
                onClick = onExpand,
            )
        }
    }
}

@Composable
private fun Eta(
    state: NavigationScreenState,
    colors: NavigationPalette,
    modifier: Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            text = state.trip.remainingTime,
            style =
                TextStyle(
                    color = if (colors.dark) colors.accent else Color(NavigationScreenColors.GREEN),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                ),
        )
        BasicText(
            text =
                state.trip.arrivalTime?.let { arrival -> "${state.trip.remainingDistance} · $arrival" }
                    ?: state.trip.remainingDistance,
            style = TextStyle(color = colors.muted, fontSize = 13.5.sp),
        )
    }
}

@Composable
private fun TripSheet(
    state: NavigationScreenState,
    strings: Strings,
    colors: NavigationPalette,
    controls: Set<NavigationControl>,
    onEnd: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCollapse: () -> Unit,
    onCameraAndAlerts: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    colors.muted.copy(alpha = 0.12f),
                    RoundedCornerShape(topStart = SHEET_CORNER_DP.dp, topEnd = SHEET_CORNER_DP.dp),
                ).background(
                    colors.surface,
                    RoundedCornerShape(topStart = SHEET_CORNER_DP.dp, topEnd = SHEET_CORNER_DP.dp),
                ).padding(horizontal = SHEET_PADDING_DP.dp),
    ) {
        SheetHandle(colors)
        TripRow(
            glyph = "\u2691",
            title = state.trip.destination,
            detail = TripDetail(state.trip, strings),
            trailing = state.trip.remainingTime,
            colors = colors,
        )
        state.readout.stopName?.let { name ->
            TripRow(
                glyph = "\u25C6",
                title = strings.resolve(NavigationScreenStrings.NEXT_STOP, listOf(name)),
                detail = state.readout.stopDistance,
                trailing = state.readout.stopTime,
                colors = colors,
            )
        }
        Spacer(Modifier.height(8.dp))
        SpeedTiles(state.trip, strings, colors)
        Spacer(Modifier.height(10.dp))
        if (NavigationControl.CameraAndAlerts in controls) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onCameraAndAlerts).padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Glyph("\u2261", 20, colors.muted)
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = strings.resolve(NavigationScreenStrings.CAMERA_AND_ALERTS),
                        style = TextStyle(color = colors.ink, fontSize = 14.5.sp),
                    )
                    BasicText(
                        text = CameraAndAlertsDetail(state, strings),
                        style = TextStyle(color = colors.muted, fontSize = 12.sp),
                    )
                }
                Glyph("\u203A", 18, colors.muted)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (NavigationControl.End in controls) {
                EndPill(
                    label = strings.resolve(NavigationScreenStrings.END),
                    colors = colors,
                    onClick = onEnd,
                    modifier = Modifier.weight(1f).height(50.dp),
                )
            }
            PrimaryPill(
                label =
                    strings.resolve(
                        if (NavigationControl.Resume in controls) {
                            NavigationScreenStrings.RESUME
                        } else {
                            NavigationScreenStrings.PAUSE
                        },
                    ),
                glyph = if (NavigationControl.Resume in controls) "\u25B6" else "\u2016",
                background = colors.soft,
                ink = colors.ink,
                onClick = if (NavigationControl.Resume in controls) onResume else onPause,
                modifier = Modifier.weight(1f).height(50.dp),
            )
            RoundButton(
                glyph = "\u2304",
                description = strings.resolve(NavigationScreenStrings.COLLAPSE),
                colors = colors,
                onClick = onCollapse,
                size = 40,
            )
        }
    }
}

@Composable
private fun SettingsSheet(
    state: NavigationScreenState,
    strings: Strings,
    colors: NavigationPalette,
    controls: Set<NavigationControl>,
    onBack: () -> Unit,
    onReset: () -> Unit,
    onCameraAutoZoom: (Boolean) -> Unit,
    onCameraAutoCentre: (Boolean) -> Unit,
    onCameraTilt: (Boolean) -> Unit,
    onAlertSwitch: (NavigationAlertSwitch, Boolean) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    colors.muted.copy(alpha = 0.12f),
                    RoundedCornerShape(topStart = SHEET_CORNER_DP.dp, topEnd = SHEET_CORNER_DP.dp),
                ).background(
                    colors.surface,
                    RoundedCornerShape(topStart = SHEET_CORNER_DP.dp, topEnd = SHEET_CORNER_DP.dp),
                ).padding(horizontal = SHEET_PADDING_DP.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        SheetHandle(colors)
        BasicText(
            text = strings.resolve(NavigationScreenStrings.CAMERA_AND_ALERTS),
            style = TextStyle(color = colors.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold),
        )
        BasicText(
            text = strings.resolve(NavigationScreenStrings.SETTINGS_SUBTITLE, listOf(state.trip.profile)),
            style = TextStyle(color = colors.muted, fontSize = 12.5.sp),
        )
        SectionLabel(strings.resolve(NavigationScreenStrings.CAMERA), colors)
        SwitchRow(
            glyph = "\u2315",
            label = strings.resolve(NavigationScreenStrings.AUTO_ZOOM),
            detail = strings.resolve(NavigationScreenStrings.AUTO_ZOOM_DETAIL),
            checked = state.camera.autoZoom,
            colors = colors,
            control = NavigationControl.AutoZoom,
            controls = controls,
            onChange = onCameraAutoZoom,
        )
        SwitchRow(
            glyph = "\u25CE",
            label = strings.resolve(NavigationScreenStrings.AUTO_CENTRE),
            detail = strings.resolve(NavigationScreenStrings.AUTO_CENTRE_DETAIL),
            checked = state.camera.autoCentre,
            colors = colors,
            control = NavigationControl.AutoCentre,
            controls = controls,
            onChange = onCameraAutoCentre,
        )
        SwitchRow(
            glyph = "\u25B2",
            label = strings.resolve(NavigationScreenStrings.THREE_D),
            detail = strings.resolve(NavigationScreenStrings.THREE_D_DETAIL),
            checked = state.camera.tilt,
            colors = colors,
            control = NavigationControl.Tilt,
            controls = controls,
            onChange = onCameraTilt,
        )
        SectionLabel(alertSectionLabel(state.alertSwitches, strings), colors)
        AlertChips(
            state = state,
            strings = strings,
            colors = colors,
            controls = controls,
            onChange = onAlertSwitch,
        )
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoundButton(
                glyph = "\u2039",
                description = strings.resolve(NavigationScreenStrings.BACK),
                colors = colors,
                onClick = onBack,
                size = 50,
            )
            if (NavigationControl.ResetToProfile in controls) {
                PrimaryPill(
                    label = strings.resolve(NavigationScreenStrings.RESET),
                    glyph = "",
                    background = colors.soft,
                    ink = colors.ink,
                    onClick = onReset,
                    modifier = Modifier.weight(1f).height(50.dp),
                )
            }
        }
    }
}

@Composable
private fun TripRow(
    glyph: String,
    title: String,
    detail: String?,
    trailing: String?,
    colors: NavigationPalette,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Glyph(glyph, 20, colors.accent)
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = TextStyle(color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
            detail?.let {
                BasicText(text = it, style = TextStyle(color = colors.muted, fontSize = 12.sp))
            }
        }
        trailing?.let {
            BasicText(
                text = it,
                style = TextStyle(color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

@Composable
private fun SpeedTiles(
    trip: NavigationTripSummary,
    strings: Strings,
    colors: NavigationPalette,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpeedTile(strings.resolve(NavigationScreenStrings.SPEED_CURRENT), trip.speedCurrent, strings, colors, Modifier.weight(1f))
        SpeedTile(strings.resolve(NavigationScreenStrings.SPEED_AVERAGE), trip.speedAverage, strings, colors, Modifier.weight(1f))
        SpeedTile(strings.resolve(NavigationScreenStrings.SPEED_MAX), trip.speedMax, strings, colors, Modifier.weight(1f))
    }
}

@Composable
private fun SpeedTile(
    label: String,
    value: String?,
    strings: Strings,
    colors: NavigationPalette,
    modifier: Modifier,
) {
    Column(
        modifier =
            modifier
                .background(colors.soft, RoundedCornerShape(SPEED_TILE_CORNER_DP.dp))
                .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        BasicText(text = label, style = TextStyle(color = colors.muted, fontSize = 11.5.sp))
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(
                text = value ?: DASH,
                style = TextStyle(color = colors.ink, fontSize = 21.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.width(2.dp))
            BasicText(
                text = strings.resolve(NavigationScreenStrings.SPEED_UNIT),
                style =
                    TextStyle(
                        color = colors.muted,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertChips(
    state: NavigationScreenState,
    strings: Strings,
    colors: NavigationPalette,
    controls: Set<NavigationControl>,
    onChange: (NavigationAlertSwitch, Boolean) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NavigationAlertSwitch.entries.forEach { switch ->
            if (alertControl(switch) !in controls) {
                return@forEach
            }
            val on = state.alertSwitchEnabled(switch)
            Box(
                modifier =
                    Modifier
                        .height(CHIP_HEIGHT_DP.dp)
                        .background(
                            if (on) colors.accent.copy(alpha = 0.16f) else colors.soft,
                            RoundedCornerShape(CHIP_CORNER_DP.dp),
                        ).clickable { onChange(switch, !on) }
                        .padding(horizontal = CHIP_PADDING_HORIZONTAL_DP.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = strings.resolve(alertSwitchLabel(switch)),
                    style =
                        TextStyle(
                            color = if (on) colors.accent else colors.muted,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    glyph: String,
    label: String,
    detail: String,
    checked: Boolean,
    colors: NavigationPalette,
    control: NavigationControl,
    controls: Set<NavigationControl>,
    onChange: (Boolean) -> Unit,
) {
    if (control !in controls) {
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Glyph(glyph, 20, colors.muted)
        Column(modifier = Modifier.weight(1f)) {
            BasicText(text = label, style = TextStyle(color = colors.ink, fontSize = 14.5.sp))
            BasicText(text = detail, style = TextStyle(color = colors.muted, fontSize = 12.sp))
        }
        Switch(checked, colors)
    }
}

@Composable
private fun Switch(
    checked: Boolean,
    colors: NavigationPalette,
) {
    Box(
        modifier =
            Modifier
                .width(SWITCH_WIDTH_DP.dp)
                .height(SWITCH_HEIGHT_DP.dp)
                .background(
                    if (checked) colors.accent else colors.muted.copy(alpha = 0.35f),
                    RoundedCornerShape(SWITCH_HEIGHT_DP.dp),
                ).padding(horizontal = 3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier =
                Modifier
                    .size(SWITCH_KNOB_DP.dp)
                    .background(Color.White, RoundedCornerShape(SWITCH_KNOB_DP.dp)),
        )
    }
}

@Composable
private fun SectionLabel(
    text: String,
    colors: NavigationPalette,
) {
    BasicText(
        text = text.uppercase(),
        modifier = Modifier.padding(top = 14.dp, bottom = 5.dp),
        style =
            TextStyle(
                color = colors.muted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
            ),
    )
}

@Composable
private fun SheetHandle(colors: NavigationPalette) {
    Box(
        modifier =
            Modifier
                .padding(top = 9.dp, bottom = 10.dp)
                .width(SHEET_HANDLE_WIDTH_DP.dp)
                .height(SHEET_HANDLE_HEIGHT_DP.dp)
                .background(if (colors.dark) Color(0xFF55595E) else Color(0xFFCFCCC6), RoundedCornerShape(2.dp)),
    )
}

@Composable
private fun EndPill(
    label: String,
    colors: NavigationPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .height(END_HEIGHT_DP.dp)
                .background(colors.dangerSoft, RoundedCornerShape(END_CORNER_DP.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Glyph("\u00D7", 18, colors.danger)
        BasicText(
            text = label,
            style = TextStyle(color = colors.danger, fontSize = 15.sp, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun PrimaryPill(
    label: String,
    glyph: String,
    background: Color,
    ink: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .background(background, RoundedCornerShape(25.dp))
                .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph.isNotEmpty()) {
            Glyph(glyph, 18, ink)
            Spacer(Modifier.width(7.dp))
        }
        BasicText(
            text = label,
            style = TextStyle(color = ink, fontSize = 15.5.sp, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun RoundButton(
    glyph: String,
    description: String,
    colors: NavigationPalette,
    onClick: () -> Unit,
    size: Int = ROUND_BUTTON_DP,
) {
    Box(
        modifier =
            Modifier
                .size(size.dp)
                .background(colors.soft, RoundedCornerShape((size / 2).dp))
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(glyph, if (size >= RECENTRE_DP) 24 else 22, colors.ink)
    }
}

@Composable
private fun RecentreButton(
    buttonLabel: String,
    colors: NavigationPalette,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .size(RECENTRE_DP.dp)
                .border(1.dp, colors.muted.copy(alpha = 0.18f), RoundedCornerShape((RECENTRE_DP / 2).dp))
                .background(colors.surface, RoundedCornerShape((RECENTRE_DP / 2).dp))
                .clickable(onClick = onClick)
                .semantics { contentDescription = buttonLabel },
        contentAlignment = Alignment.Center,
    ) {
        Glyph("\u25CE", 24, colors.accent)
    }
}

@Composable
private fun Glyph(
    glyph: String,
    size: Int,
    color: Color,
) {
    BasicText(
        text = glyph,
        style =
            TextStyle(
                color = color,
                fontSize = size.sp,
                fontWeight = FontWeight.SemiBold,
            ),
    )
}

private fun glassChip(colors: NavigationPalette): Modifier =
    Modifier
        .height(CHIP_HEIGHT_DP.dp)
        .background(colors.glass, RoundedCornerShape(CHIP_CORNER_DP.dp))
        .padding(horizontal = CHIP_PADDING_HORIZONTAL_DP.dp)

private fun alertGlyph(glyph: NavigationAlertGlyph): String =
    when (glyph) {
        NavigationAlertGlyph.Crossing -> "\u2715"
        NavigationAlertGlyph.Tunnel -> "\u2229"
        NavigationAlertGlyph.Hazard -> "!"
        NavigationAlertGlyph.Ford -> "\u2248"
        NavigationAlertGlyph.Reroute -> "\u27F3"
        NavigationAlertGlyph.Signal -> "\u25D4"
        NavigationAlertGlyph.Arrival -> "\u2691"
    }

private fun TripDetail(
    trip: NavigationTripSummary,
    strings: Strings,
): String =
    trip.arrivalTime?.let { arrival ->
        strings.resolve(NavigationScreenStrings.TRIP_LEFT, listOf(trip.remainingDistance, arrival))
    } ?: strings.resolve(NavigationScreenStrings.TRIP_LEFT_NO_ARRIVAL, listOf(trip.remainingDistance))

private fun CameraAndAlertsDetail(
    state: NavigationScreenState,
    strings: Strings,
): String {
    val allOn =
        state.camera == NavigationCameraSwitches(autoZoom = true, autoCentre = true, tilt = true)
    val camera =
        strings.resolve(
            if (allOn) {
                NavigationScreenStrings.CAMERA_SUMMARY_ALL_ON
            } else {
                NavigationScreenStrings.CAMERA_SUMMARY_CUSTOM
            },
        )
    val alerts =
        strings.resolve(
            if (state.alertSwitches.enabledCount == 0) {
                NavigationScreenStrings.ALERTS_SUMMARY_OFF
            } else {
                NavigationScreenStrings.ALERTS_SUMMARY_ON
            },
        )
    return "${strings.resolve(NavigationScreenStrings.THIS_TRIP)} · $camera · $alerts"
}

private fun alertSectionLabel(
    switches: NavigationAlertSwitches,
    strings: Strings,
): String =
    if (switches.enabledCount == 0) {
        strings.resolve(NavigationScreenStrings.ALERTS_ALL_OFF)
    } else {
        strings.resolve(NavigationScreenStrings.ALERTS_ON, listOf(switches.enabledCount.toString()))
    }

/** The two banners the round 5 screen allows: one full and one slim. */
private const val MAX_BANNERS = 2
