package nl.r1a.grassmap.plugins.downloads.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.model.CatalogPartKind
import nl.r1a.grassmap.plugin.api.model.DownloadPartRow
import nl.r1a.grassmap.plugin.api.model.DownloadProblem
import nl.r1a.grassmap.plugin.api.model.DownloadProblemKind
import nl.r1a.grassmap.plugin.api.model.DownloadRow
import nl.r1a.grassmap.plugin.api.model.DownloadRowStatus
import nl.r1a.grassmap.plugin.api.model.DownloadTransfer
import nl.r1a.grassmap.plugin.api.model.DownloadTransferState
import nl.r1a.grassmap.plugin.api.model.DownloadsState
import nl.r1a.grassmap.plugin.api.model.FirstRunOffer
import nl.r1a.grassmap.plugin.api.model.FirstRunOfferKind

private val SURFACE = Color(0xFFF5F4F1)
private val CARD = Color(0xFFFFFFFF)
private val INK = Color(0xFF1F2329)
private val MUTED = Color(0xFF5D6168)
private val ACCENT = Color(0xFF1F7A4D)
private val DANGER = Color(0xFFB3261E)
private val BORDER = Color(0xFFE3E1DC)

private const val CARD_CORNER_DP = 16
private const val CARD_PADDING_DP = 14
private const val SCREEN_PADDING_DP = 16
private const val GAP_DP = 10
private const val TITLE_FONT_SP = 26
private const val LABEL_FONT_SP = 12
private const val ROW_TITLE_FONT_SP = 17
private const val BODY_FONT_SP = 14
private const val ACTION_FONT_SP = 15
private const val PROGRESS_BAR_DP = 4

/**
 * The downloads screen (T-0504), drawn from the published state.
 *
 * The screen reads no store and keeps no facts: [state] carries every row, its
 * parts, its sizes, its data date, its transfer and its typed problem, and the
 * callbacks go back to the plugin's commands. The shell hosts this content and
 * owns the route, the sheet position and the server form's visibility.
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
    onOpenRegion: (String) -> Unit,
    onCloseSheet: () -> Unit,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRefresh: () -> Unit,
    onSaveServer: (String, String) -> Unit,
    onTokenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(SURFACE)
                // Twenty-five oblast rows do not fit one screen, and the first-run
                // offer sits above them, so the screen scrolls as one column.
                .verticalScroll(rememberScrollState())
                .padding(SCREEN_PADDING_DP.dp)
                .widthIn(max = MAX_WIDTH_DP.dp),
        verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        ) {
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
                    text = stringResource(R.string.downloads_refresh),
                    onClick = onRefresh,
                    quiet = true,
                )
                ActionButton(
                    text = stringResource(R.string.downloads_token_settings),
                    onClick = onTokenSettings,
                    quiet = true,
                )
            }
        }

        if (showServerForm) {
            ServerForm(serverUrl = state.serverUrl, onSave = onSaveServer)
        }

        state.catalogProblem?.let { problem -> ProblemLine(problem) }

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
                )
            }
        }

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

        state.selected?.let { row ->
            RegionSheet(
                row = row,
                preferUkrainian = preferUkrainian,
                onClose = onCloseSheet,
                onDownload = onDownload,
                onCancel = onCancel,
                onRemove = onRemove,
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun FirstRunRow(
    offer: FirstRunOffer,
    preferUkrainian: Boolean,
    onDownload: (String) -> Unit,
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
    val action = offer.kind == FirstRunOfferKind.WORLD || offer.kind == FirstRunOfferKind.REGION
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
                if (offer.parts.size > 1) {
                    for (part in offer.parts) {
                        PartRow(part)
                    }
                }
                if (offer.installed) {
                    BasicText(
                        text = stringResource(R.string.downloads_on_this_phone),
                        style = TextStyle(color = ACCENT, fontSize = BODY_FONT_SP.sp, fontWeight = FontWeight.Medium),
                    )
                }
            }
            if (action && !offer.installed) {
                ActionButton(
                    text = stringResource(R.string.downloads_download_short),
                    onClick = { onDownload(offer.region) },
                )
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
                row.problem?.let { problem -> ProblemLine(problem) }
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
            PartRow(part)
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
private fun PartRow(part: DownloadPartRow) {
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
                .background(BORDER),
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
        modifier = if (onClick == null) base else base.clickable(onClick = onClick),
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
    val background =
        when {
            quiet -> BORDER
            destructive -> CARD
            else -> ACCENT
        }
    val ink =
        when {
            destructive -> DANGER
            quiet -> INK
            else -> Color.White
        }
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(CARD_CORNER_DP.dp))
                .background(background)
                .clickable(onClick = onClick)
                .padding(horizontal = CARD_PADDING_DP.dp, vertical = GAP_DP.dp),
    ) {
        BasicText(
            text = text,
            style = TextStyle(color = ink, fontSize = ACTION_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
        )
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
