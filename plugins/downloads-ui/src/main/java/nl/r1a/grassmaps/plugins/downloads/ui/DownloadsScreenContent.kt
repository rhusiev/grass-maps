package nl.r1a.grassmaps.plugins.downloads.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.api.model.CatalogPartKind
import nl.r1a.grassmaps.plugin.api.model.DownloadPartRow
import nl.r1a.grassmaps.plugin.api.model.DownloadProblem
import nl.r1a.grassmaps.plugin.api.model.DownloadProblemKind
import nl.r1a.grassmaps.plugin.api.model.DownloadRow
import nl.r1a.grassmaps.plugin.api.model.DownloadRowStatus
import nl.r1a.grassmaps.plugin.api.model.DownloadTransfer
import nl.r1a.grassmaps.plugin.api.model.DownloadTransferState
import nl.r1a.grassmaps.plugin.api.model.DownloadsState
import nl.r1a.grassmaps.plugin.api.model.FirstRunOffer
import nl.r1a.grassmaps.plugin.api.model.FirstRunOfferKind
import nl.r1a.grassmaps.plugin.api.model.MobileDataPolicy
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors

private val SURFACE: Color
    @Composable get() = LocalShellColors.current.map

private val CARD: Color
    @Composable get() = LocalShellColors.current.surface

private val SOFT: Color
    @Composable get() = LocalShellColors.current.soft

private val INK: Color
    @Composable get() = LocalShellColors.current.ink

private val MUTED: Color
    @Composable get() = LocalShellColors.current.muted

private val ACCENT: Color
    @Composable get() = LocalShellColors.current.accent

private val DANGER: Color
    @Composable get() = LocalShellColors.current.danger

private val BORDER: Color
    @Composable get() = LocalShellColors.current.line

private val CONTROL_BORDER: Color
    @Composable get() = LocalShellColors.current.controlBorder

private const val CARD_CORNER_DP = 16
private const val CARD_PADDING_DP = 14
private const val SCREEN_PADDING_DP = 16
private const val SHEET_MAX_HEIGHT_DP = 360
private const val BACK_TOUCH_DP = 8
private const val BACK_GLYPH_SP = 28
private const val BACK_GLYPH = "\u2039"
private const val GAP_DP = 10
private const val TITLE_FONT_SP = 26
private const val LABEL_FONT_SP = 12
private const val ROW_TITLE_FONT_SP = 17
private const val BODY_FONT_SP = 14
private const val ACTION_FONT_SP = 15
private const val PROGRESS_BAR_DP = 4
private const val SWITCH_ROW_HEIGHT_DP = 48
private const val SWITCH_TRACK_WIDTH_DP = 52
private const val SWITCH_TRACK_HEIGHT_DP = 32
private const val SWITCH_KNOB_DP = 24
private const val SWITCH_INSET_DP = 4
private const val SWITCH_BORDER_DP = 1

/**
 * The downloads screen (T-0504), drawn from the published state.
 *
 * The screen reads no store and keeps no facts: [state] carries every row, its
 * parts, its sizes, its data date, its transfer and its typed problem, and the
 * callbacks go back to the plugin's commands. The shell hosts this content and
 * owns the route, the sheet position and the server form's visibility.
 *
 * [onClose] leaves the screen. The screen is a full-screen overlay on the map,
 * so it needs its own way out: a back chevron in the header, next to the
 * title, and the shell's system-back handler both call it.
 *
 * The server form is where a token is typed. It is a write-only field: the
 * state says only whether a token is stored ([DownloadsState.tokenPresent]),
 * and the entered value goes straight to [onSaveServer], never to a row.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun DownloadsScreenContent(
    state: DownloadsState,
    preferUkrainian: Boolean,
    showServerForm: Boolean,
    onClose: () -> Unit,
    onOpenRegion: (String) -> Unit,
    onCloseSheet: () -> Unit,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onUpdateAll: () -> Unit,
    onSaveServer: (String, String) -> Unit,
    onTokenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onSetMobileData: (Boolean) -> Unit = {},
    onUseMobileDataNow: () -> Unit = {},
    onSetAutoUpdate: (Boolean) -> Unit = {},
    onSetDownloadsEnabled: (Boolean) -> Unit = {},
    onSearch: (String) -> Unit = {},
) {
    val close = stringResource(R.string.downloads_close)
    val switchNotice =
        downloadsSwitchNotice(
            labels =
                DownloadsSwitchLabels(
                    title = stringResource(R.string.downloads_switch_title),
                    onText = stringResource(R.string.downloads_switch_on),
                    offText = stringResource(R.string.downloads_switch_off),
                    privacyNote = stringResource(R.string.downloads_server_sees),
                    offMessage = stringResource(R.string.downloads_failed_switch_off),
                    turnOnAction = stringResource(R.string.downloads_switch_turn_on),
                ),
            enabled = state.downloadsEnabled,
        )
    // The top scrolls and only reads, apart from a tap on the map. Everything
    // else you press sits in the bar below it, within reach of the thumb.
    Column(
        modifier =
            modifier
                .background(SURFACE)
                .statusBarsPadding()
                .imePadding()
                .widthIn(max = MAX_WIDTH_DP.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    // Twenty-five oblast rows do not fit one screen, and the first-run
                    // offer sits above them, so the list scrolls as one column.
                    .verticalScroll(rememberScrollState())
                    .padding(SCREEN_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = BACK_GLYPH,
                    style = TextStyle(color = INK, fontSize = BACK_GLYPH_SP.sp),
                    modifier =
                        Modifier
                            .clickable(role = Role.Button, onClick = onClose)
                            .semantics { contentDescription = close }
                            .padding(BACK_TOUCH_DP.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = stringResource(R.string.downloads_title),
                        style = TextStyle(color = INK, fontSize = TITLE_FONT_SP.sp, fontWeight = FontWeight.Bold),
                    )
                    BasicText(
                        text = state.serverUrl,
                        style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(SPACED_DP.dp)) {
                    ActionButton(
                        text = stringResource(R.string.downloads_update_all),
                        onClick = onUpdateAll,
                        quiet = true,
                    )
                    ActionButton(
                        text = stringResource(R.string.downloads_token_settings),
                        onClick = onTokenSettings,
                        quiet = true,
                    )
                }
            }

            // The switch is the first control after the header, so the off
            // state explains itself where a store user lands. Its alert card
            // carries the same action for someone who taps Download first.
            DownloadsSwitchRow(notice = switchNotice, onCheckedChange = onSetDownloadsEnabled)
            switchNotice.alert?.let { alert -> DownloadsOffAlertCard(alert = alert, onEnable = { onSetDownloadsEnabled(true) }) }

            if (state.outlines.isNotEmpty()) {
                RegionsMiniMap(
                    outlines = state.outlines,
                    statuses = state.outlineStatuses,
                    selectedRegion = state.selectedRegion,
                    description = stringResource(R.string.downloads_map_description),
                    onTapRegion = onOpenRegion,
                )
            }

            if (state.appliesAtNextStart) {
                BasicText(
                    text = stringResource(R.string.downloads_applies_at_next_start),
                    style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp),
                )
            }

            if (showServerForm) {
                ServerForm(serverUrl = state.serverUrl, onSave = onSaveServer)
            }

            MobileDataCard(
                state = state,
                onSetMobileData = onSetMobileData,
                onUseMobileDataNow = onUseMobileDataNow,
                onSetAutoUpdate = onSetAutoUpdate,
            )

            // The switch's alert card carries the off message, so the plain red
            // line stays only for problems the switch cannot explain.
            state.catalogProblem
                ?.takeIf { it.kind != DownloadProblemKind.SWITCH_OFF }
                ?.let { problem -> ProblemLine(problem) }

            if (!state.catalogLoaded && state.catalogProblem == null && state.firstRun.isEmpty()) {
                SectionLabel(stringResource(R.string.downloads_empty))
            }

            if (state.firstRun.isNotEmpty() && state.installedRows.isEmpty()) {
                SectionLabel(stringResource(R.string.downloads_first_run_title))
                for (offer in state.firstRun) {
                    FirstRunRow(
                        offer = offer,
                        preferUkrainian = preferUkrainian,
                        onDownload = onDownload,
                        onCancel = onCancel,
                    )
                }
            }

            if (state.query.isNotBlank()) {
                SectionLabel(stringResource(R.string.downloads_search_results))
                if (state.searchResults.isEmpty()) {
                    BasicText(
                        text = stringResource(R.string.downloads_search_empty),
                        style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp),
                    )
                }
                for (row in state.searchResults) {
                    RegionRow(
                        row = row,
                        preferUkrainian = preferUkrainian,
                        onOpen = onOpenRegion,
                        onDownload = onDownload,
                        onCancel = onCancel,
                    )
                }
            } else {
                if (state.installedRows.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.downloads_on_this_phone))
                    for (row in state.installedRows) {
                        RegionRow(
                            row = row,
                            preferUkrainian = preferUkrainian,
                            onOpen = onOpenRegion,
                            onDownload = onDownload,
                            onCancel = onCancel,
                        )
                    }
                }

                if (state.availableRows.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.downloads_available))
                    for (row in state.availableRows) {
                        RegionRow(
                            row = row,
                            preferUkrainian = preferUkrainian,
                            onOpen = onOpenRegion,
                            onDownload = onDownload,
                            onCancel = onCancel,
                        )
                    }
                }
            }
        }
        DownloadsActionBar(
            state = state,
            preferUkrainian = preferUkrainian,
            onCloseSheet = onCloseSheet,
            onDownload = onDownload,
            onCancel = onCancel,
            onRemove = onRemove,
            onSearch = onSearch,
        )
    }
}

/**
 * The bar at the bottom: the open region's sheet, the "near you" suggestion and
 * the search field. It stays in place while the list above it scrolls.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun DownloadsActionBar(
    state: DownloadsState,
    preferUkrainian: Boolean,
    onCloseSheet: () -> Unit,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSearch: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SCREEN_PADDING_DP.dp, vertical = GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
    ) {
        val selected = state.selected
        if (selected != null) {
            // The sheet lists every part, so it scrolls inside its own height
            // and leaves the search field in view.
            Column(modifier = Modifier.heightIn(max = SHEET_MAX_HEIGHT_DP.dp).verticalScroll(rememberScrollState())) {
                RegionSheet(
                    row = selected,
                    preferUkrainian = preferUkrainian,
                    onClose = onCloseSheet,
                    onDownload = onDownload,
                    onCancel = onCancel,
                    onRemove = onRemove,
                )
            }
        }
        val nearMe = state.nearMe
        if (nearMe != null && selected == null) {
            NearMeCard(row = nearMe, preferUkrainian = preferUkrainian, onDownload = onDownload, onCancel = onCancel)
        }
        SearchField(query = state.query, onSearch = onSearch)
    }
}

/** The oblast the phone is in, offered while it is not on the phone. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun NearMeCard(
    row: DownloadRow,
    preferUkrainian: Boolean,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    Card {
        SectionLabel(stringResource(R.string.downloads_near_you))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SPACED_DP.dp)) {
                BasicText(
                    text = row.name(preferUkrainian) ?: row.region,
                    style = TextStyle(color = INK, fontSize = ROW_TITLE_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
                )
                BasicText(
                    text = sizeAndDate(row.bytes, row.dataDate),
                    style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp),
                )
                row.transfer?.let { transfer -> TransferLine(transfer) }
                row.problem
                    ?.takeIf { it.kind != DownloadProblemKind.SWITCH_OFF }
                    ?.let { problem -> ProblemLine(problem) }
            }
            if (row.transfer != null) {
                ActionButton(
                    text = stringResource(R.string.downloads_cancel),
                    onClick = { onCancel(row.region) },
                    quiet = true,
                )
            } else {
                ActionButton(
                    text = stringResource(R.string.downloads_download_short),
                    onClick = { onDownload(row.region) },
                )
            }
        }
    }
}

/** The search field. The text is the user's own, so it lives here and goes out on every change. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun SearchField(
    query: String,
    onSearch: (String) -> Unit,
) {
    var text by remember { mutableStateOf(query) }
    val hint = stringResource(R.string.downloads_search_hint)
    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            onSearch(it)
        },
        singleLine = true,
        textStyle = TextStyle(color = INK, fontSize = BODY_FONT_SP.sp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(CARD_CORNER_DP.dp))
                .background(CARD)
                .border(1.dp, CONTROL_BORDER, RoundedCornerShape(CARD_CORNER_DP.dp))
                .padding(CARD_PADDING_DP.dp)
                .semantics { contentDescription = hint },
        decorationBox = { innerTextField ->
            Box {
                if (text.isEmpty()) {
                    BasicText(
                        text = hint,
                        style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp),
                        // The field already carries the hint as its label.
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                innerTextField()
            }
        },
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun FirstRunRow(
    offer: FirstRunOffer,
    preferUkrainian: Boolean,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    val name = offer.name(preferUkrainian) ?: offer.region
    val title =
        when (offer.kind) {
            FirstRunOfferKind.WORLD -> stringResource(R.string.downloads_world_overview)
            FirstRunOfferKind.REGION -> name
            FirstRunOfferKind.ROUTING -> stringResource(R.string.downloads_routing_for, name)
            FirstRunOfferKind.ELEVATION_30M -> stringResource(R.string.downloads_elevation_30m_for, name)
        }
    val detail =
        when (offer.kind) {
            FirstRunOfferKind.WORLD -> stringResource(R.string.downloads_world_detail)
            FirstRunOfferKind.REGION -> stringResource(R.string.downloads_region_detail)
            FirstRunOfferKind.ROUTING -> stringResource(R.string.downloads_routing_detail)
            FirstRunOfferKind.ELEVATION_30M -> stringResource(R.string.downloads_elevation_30m_detail)
        }
    val action = offer.kind != FirstRunOfferKind.ROUTING
    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SPACED_DP.dp)) {
                BasicText(
                    text = title,
                    style = TextStyle(color = INK, fontSize = ROW_TITLE_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
                )
                BasicText(text = detail, style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp))
                if (offer.kind == FirstRunOfferKind.REGION && !offer.installed) {
                    BasicText(
                        text = stringResource(R.string.downloads_you_are_here),
                        style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp),
                    )
                }
                BasicText(
                    text = stringResource(R.string.downloads_size_only, DownloadsFormat.size(offer.bytes)),
                    style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp),
                )
                offer.transfer?.let { transfer -> TransferLine(transfer) }
                if (offer.parts.size > 1) {
                    for (part in offer.parts) {
                        PartRow(part, installable = false, onDownload = onDownload, onCancel = {})
                    }
                }
                if (offer.installed) {
                    BasicText(
                        text = stringResource(R.string.downloads_on_this_phone),
                        style = TextStyle(color = ACCENT, fontSize = BODY_FONT_SP.sp, fontWeight = FontWeight.Medium),
                    )
                }
            }
            when {
                offer.transfer != null -> {
                    ActionButton(
                        text = stringResource(R.string.downloads_cancel),
                        onClick = { onCancel(offer.downloadId ?: offer.region) },
                        quiet = true,
                    )
                }

                action && !offer.installed -> {
                    ActionButton(
                        text = stringResource(R.string.downloads_download_short),
                        onClick = { onDownload(offer.downloadId ?: offer.region) },
                    )
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun RegionRow(
    row: DownloadRow,
    preferUkrainian: Boolean,
    onOpen: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    Card(onClick = { onOpen(row.region) }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SPACED_DP.dp)) {
                BasicText(
                    text = row.name(preferUkrainian) ?: row.region,
                    style = TextStyle(color = INK, fontSize = ROW_TITLE_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
                )
                BasicText(
                    text = sizeAndDate(row.bytes, row.dataDate),
                    style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp),
                )
                row.transfer?.let { transfer -> TransferLine(transfer) }
                row.problem
                    ?.takeIf { it.kind != DownloadProblemKind.SWITCH_OFF }
                    ?.let { problem -> ProblemLine(problem) }
                if (row.status == DownloadRowStatus.INSTALLED) {
                    BasicText(
                        text = stringResource(R.string.downloads_up_to_date),
                        style = TextStyle(color = ACCENT, fontSize = BODY_FONT_SP.sp),
                    )
                }
            }
            when {
                row.transfer != null -> {
                    ActionButton(
                        text = stringResource(R.string.downloads_cancel),
                        onClick = { onCancel(row.region) },
                        quiet = true,
                    )
                }

                row.status != DownloadRowStatus.INSTALLED -> {
                    ActionButton(
                        text = stringResource(R.string.downloads_download_short),
                        onClick = { onDownload(row.region) },
                    )
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun RegionSheet(
    row: DownloadRow,
    preferUkrainian: Boolean,
    onClose: () -> Unit,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SPACED_DP.dp)) {
                BasicText(
                    text = row.name(preferUkrainian) ?: row.region,
                    style = TextStyle(color = INK, fontSize = ROW_TITLE_FONT_SP.sp, fontWeight = FontWeight.Bold),
                )
                BasicText(
                    text = sizeAndDate(row.bytes, row.dataDate),
                    style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp),
                )
            }
            ActionButton(text = stringResource(R.string.downloads_close), onClick = onClose, quiet = true)
        }
        Spacer(modifier = Modifier.height(GAP_DP.dp))
        SectionLabel(stringResource(R.string.downloads_parts_title))
        for (part in row.parts) {
            PartRow(part, installable = row.isInstalled, onDownload = onDownload, onCancel = onCancel)
        }
        Spacer(modifier = Modifier.height(GAP_DP.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
            if (row.isInstalled && row.transfer == null) {
                ActionButton(
                    text = stringResource(R.string.downloads_remove_region),
                    onClick = { onRemove(row.region) },
                    destructive = true,
                )
            }
            if (row.transfer != null) {
                ActionButton(
                    text = stringResource(R.string.downloads_cancel),
                    onClick = { onCancel(row.region) },
                    quiet = true,
                )
            } else {
                ActionButton(
                    text = stringResource(R.string.downloads_download, DownloadsFormat.size(row.bytes)),
                    onClick = { onDownload(row.region) },
                )
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PartRow(
    part: DownloadPartRow,
    installable: Boolean,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            BasicText(
                text = partLabel(part.kind),
                style = TextStyle(color = INK, fontSize = BODY_FONT_SP.sp),
            )
            BasicText(
                text = DownloadsFormat.size(part.bytes),
                style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp),
            )
        }
        if (part.installed) {
            BasicText(
                text = stringResource(R.string.downloads_on_this_phone),
                style = TextStyle(color = ACCENT, fontSize = BODY_FONT_SP.sp),
            )
        }
        val downloadId = part.downloadId
        if (downloadId != null && !part.installed) {
            val transfer = part.transfer
            if (transfer != null) {
                ActionButton(
                    text = stringResource(R.string.downloads_cancel),
                    onClick = { onCancel(downloadId) },
                    quiet = true,
                )
            } else if (installable) {
                ActionButton(
                    text = stringResource(R.string.downloads_download_short),
                    onClick = { onDownload(downloadId) },
                )
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun TransferLine(transfer: DownloadTransfer) {
    when (transfer.state) {
        DownloadTransferState.WAITING -> {
            BasicText(
                text = stringResource(R.string.downloads_waiting),
                style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp),
            )
        }

        DownloadTransferState.WAITING_FOR_WIFI -> {
            BasicText(
                text = stringResource(R.string.downloads_waiting_for_wifi),
                style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp),
            )
        }

        DownloadTransferState.VERIFYING -> {
            BasicText(
                text = stringResource(R.string.downloads_verifying),
                style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp),
            )
        }

        DownloadTransferState.DOWNLOADING -> {
            val percent = DownloadsFormat.progressPercent(transfer.bytesDone, transfer.bytesTotal)
            BasicText(
                text =
                    if (percent == null) {
                        stringResource(R.string.downloads_downloading_unknown)
                    } else {
                        stringResource(R.string.downloads_downloading, percent)
                    },
                style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp),
            )
            if (percent != null) {
                TransferBar(percent)
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun TransferBar(percent: Int) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(PROGRESS_BAR_DP.dp)
                .clip(RoundedCornerShape(PROGRESS_BAR_DP.dp))
                .background(BORDER)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(percent.coerceIn(0, 100) / 100f, 0f..1f)
                },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                    .height(PROGRESS_BAR_DP.dp)
                    .background(ACCENT),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ProblemLine(problem: DownloadProblem) {
    BasicText(
        text = problemText(problem),
        style = TextStyle(color = DANGER, fontSize = BODY_FONT_SP.sp, fontWeight = FontWeight.Medium),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SectionLabel(text: String) {
    BasicText(
        text = text,
        style = TextStyle(color = MUTED, fontSize = LABEL_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Card(
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val base =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CARD_CORNER_DP.dp))
            .background(CARD)
            .padding(CARD_PADDING_DP.dp)
    Column(
        modifier = if (onClick == null) base else base.clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(SPACED_DP.dp),
    ) {
        content()
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    quiet: Boolean = false,
    destructive: Boolean = false,
) {
    val shape = RoundedCornerShape(CARD_CORNER_DP.dp)
    val background =
        when {
            quiet -> SOFT
            destructive -> CARD
            else -> ACCENT
        }
    val ink =
        when {
            destructive -> DANGER
            quiet -> INK
            else -> LocalShellColors.current.onAccent
        }
    val outline =
        when {
            quiet -> CONTROL_BORDER
            destructive -> DANGER
            else -> ACCENT
        }
    Box(
        modifier =
            Modifier
                .clip(shape)
                .background(background, shape)
                .border(1.dp, outline, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = CARD_PADDING_DP.dp, vertical = GAP_DP.dp),
    ) {
        BasicText(
            text = text,
            style = TextStyle(color = ink, fontSize = ACTION_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/**
 * The on-screen on/off switch. The whole 48 dp row toggles it, so the touch
 * target is not the small track, and the off state names "Off" in the danger
 * token beside a visible off track.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun DownloadsSwitchRow(
    notice: DownloadsSwitchNotice,
    onCheckedChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(CARD_CORNER_DP.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = SWITCH_ROW_HEIGHT_DP.dp)
                .clip(shape)
                .background(CARD)
                .border(SWITCH_BORDER_DP.dp, if (notice.checked) ACCENT else CONTROL_BORDER, shape)
                .toggleable(value = notice.checked, role = Role.Switch, onValueChange = onCheckedChange)
                .semantics { contentDescription = "${notice.title}, ${notice.stateText}" }
                .padding(CARD_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SPACED_DP.dp)) {
            BasicText(
                text = notice.title,
                style = TextStyle(color = INK, fontSize = ROW_TITLE_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
            )
            BasicText(
                text = notice.stateText,
                style =
                    TextStyle(
                        color = if (notice.checked) ACCENT else DANGER,
                        fontSize = BODY_FONT_SP.sp,
                        fontWeight = FontWeight.Medium,
                    ),
            )
            BasicText(text = notice.privacyNote, style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp))
        }
        DownloadsSwitchTrack(checked = notice.checked)
    }
}

/** The round 9 switch track: accent when on, outlined soft when off. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun DownloadsSwitchTrack(checked: Boolean) {
    val shape = RoundedCornerShape((SWITCH_TRACK_HEIGHT_DP / 2).dp)
    Box(
        modifier =
            Modifier
                .size(SWITCH_TRACK_WIDTH_DP.dp, SWITCH_TRACK_HEIGHT_DP.dp)
                .clip(shape)
                .background(if (checked) ACCENT else SOFT, shape)
                .border(SWITCH_BORDER_DP.dp, if (checked) ACCENT else CONTROL_BORDER, shape),
    ) {
        Box(
            modifier =
                Modifier
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(SWITCH_INSET_DP.dp)
                    .size(SWITCH_KNOB_DP.dp)
                    .clip(CircleShape)
                    .background(if (checked) LocalShellColors.current.onAccent else LocalShellColors.current.offSwitchKnob),
        )
    }
}

/** The card that replaces the plain red text while downloads are off. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun DownloadsOffAlertCard(
    alert: DownloadsSwitchNotice.Alert,
    onEnable: () -> Unit,
) {
    Card {
        BasicText(
            text = alert.message,
            style = TextStyle(color = DANGER, fontSize = BODY_FONT_SP.sp, fontWeight = FontWeight.Medium),
        )
        ActionButton(text = alert.action, onClick = onEnable)
    }
}

/**
 * The mobile-data policy and, while a download waits for Wi-Fi, the one-off
 * way past it, and the monthly automatic update choice. Each button names the
 * choice it switches to.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun MobileDataCard(
    state: DownloadsState,
    onSetMobileData: (Boolean) -> Unit,
    onUseMobileDataNow: () -> Unit,
    onSetAutoUpdate: (Boolean) -> Unit,
) {
    val wifiOnly = state.mobileDataPolicy == MobileDataPolicy.WIFI_ONLY
    Card {
        SectionLabel(stringResource(R.string.downloads_mobile_data_title))
        ActionButton(
            text =
                stringResource(
                    if (wifiOnly) R.string.downloads_mobile_data_wifi_only else R.string.downloads_mobile_data_any,
                ),
            onClick = { onSetMobileData(wifiOnly) },
            quiet = true,
        )
        ActionButton(
            text =
                stringResource(
                    if (state.autoUpdate) R.string.downloads_auto_update_stop else R.string.downloads_auto_update_start,
                ),
            onClick = { onSetAutoUpdate(!state.autoUpdate) },
            quiet = true,
        )
        if (state.waitingForWifi) {
            ActionButton(
                text = stringResource(R.string.downloads_use_mobile_data_now),
                onClick = onUseMobileDataNow,
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ServerForm(
    serverUrl: String,
    onSave: (String, String) -> Unit,
) {
    var url by remember(serverUrl) { mutableStateOf(serverUrl) }
    var token by remember { mutableStateOf("") }
    Card {
        SectionLabel(stringResource(R.string.downloads_server_address))
        BasicTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            textStyle = TextStyle(color = INK, fontSize = BODY_FONT_SP.sp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(CARD_CORNER_DP.dp))
                    .background(SURFACE)
                    .border(1.dp, CONTROL_BORDER, RoundedCornerShape(CARD_CORNER_DP.dp))
                    .padding(CARD_PADDING_DP.dp),
        )
        SectionLabel(stringResource(R.string.downloads_token_field))
        BasicTextField(
            value = token,
            onValueChange = { token = it },
            singleLine = true,
            textStyle = TextStyle(color = INK, fontSize = BODY_FONT_SP.sp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(CARD_CORNER_DP.dp))
                    .background(SURFACE)
                    .border(1.dp, CONTROL_BORDER, RoundedCornerShape(CARD_CORNER_DP.dp))
                    .padding(CARD_PADDING_DP.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (token.isEmpty()) {
                        BasicText(
                            text = stringResource(R.string.downloads_token_placeholder),
                            style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp),
                        )
                    }
                    innerTextField()
                }
            },
        )
        BasicText(
            text = stringResource(R.string.downloads_server_sees),
            style = TextStyle(color = MUTED, fontSize = BODY_FONT_SP.sp),
        )
        ActionButton(
            text = stringResource(R.string.downloads_save),
            onClick = { onSave(url, token) },
        )
    }
}

@Composable
private fun partLabel(kind: CatalogPartKind): String =
    when (kind) {
        CatalogPartKind.MAP -> stringResource(R.string.downloads_part_map)
        CatalogPartKind.CONTOURS -> stringResource(R.string.downloads_part_contours)
        CatalogPartKind.SEARCH -> stringResource(R.string.downloads_part_search)
        CatalogPartKind.ELEVATION -> stringResource(R.string.downloads_part_elevation)
        CatalogPartKind.ROUTING -> stringResource(R.string.downloads_part_routing)
        CatalogPartKind.ELEVATION_30M -> stringResource(R.string.downloads_part_elevation_30m)
        CatalogPartKind.OTHER -> stringResource(R.string.downloads_part_other)
    }

@Composable
private fun problemText(problem: DownloadProblem): String {
    val key = DownloadsProblemText.key(problem.kind)
    return if (problem.kind == DownloadProblemKind.SERVER) {
        stringResource(key, problem.status.toString())
    } else {
        stringResource(key)
    }
}

@Composable
private fun sizeAndDate(
    bytes: Long,
    isoDate: String?,
): String {
    val date = DownloadsFormat.dataDate(isoDate, LocalConfiguration.current.locales[0])
    return if (date == null) {
        stringResource(R.string.downloads_size_only, DownloadsFormat.size(bytes))
    } else {
        stringResource(R.string.downloads_size_and_date, DownloadsFormat.size(bytes), date)
    }
}

private const val MAX_WIDTH_DP = 720
private const val SPACED_DP = 4
