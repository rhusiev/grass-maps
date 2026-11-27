package nl.r1a.grassmaps.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.r1a.grassmaps.app.backup.BackupExport
import nl.r1a.grassmaps.app.backup.BackupPhase
import nl.r1a.grassmaps.app.backup.BackupUiController
import nl.r1a.grassmaps.app.backup.BackupUiState
import nl.r1a.grassmaps.core.services.backup.BundleConflictChoice
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.fieldOutline
import nl.r1a.grassmaps.plugin.ui.api.floatingSurface
import nl.r1a.grassmaps.plugin.ui.api.switchOutline

private val SURFACE: Color
    @Composable get() = LocalShellColors.current.map

private val INK: Color
    @Composable get() = LocalShellColors.current.ink

private val MUTED: Color
    @Composable get() = LocalShellColors.current.muted

private val ACCENT: Color
    @Composable get() = LocalShellColors.current.accent

private val DANGER: Color
    @Composable get() = LocalShellColors.current.danger

private val SCRIM: Color
    @Composable get() = LocalShellColors.current.scrim

private val CARD: Color
    @Composable get() = LocalShellColors.current.surface

private const val TITLE_SP = 22
private const val BODY_SP = 15
private const val SMALL_SP = 13
private const val MONO_SP = 12
private const val BUTTON_HEIGHT_DP = 48
private const val PADDING_DP = 20
private const val CARD_MAX_HEIGHT_DP = 420

/**
 * The plain backup screen (T-0608/T-0609) over the map. It is functional, not
 * the designed settings surface: export writes one bundle, import shows one
 * conflict at a time and a checkbox covers the rest of that kind.
 *
 * The debug control channel opens it until the settings UI (T-0612) has its
 * entry point.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun BackupScreenHost(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by container.backupUi.state.collectAsState()
    val visible by BackupScreenSession.visible.collectAsState()
    var pending by remember { mutableStateOf<BackupExport?>(null) }

    val saveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val export = pending
            pending = null
            if (uri == null || export == null) {
                container.backupUi.exportCancelled()
                return@rememberLauncherForActivityResult
            }
            container.backupUi.exportHandled()
            scope.launch {
                val written =
                    withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.openOutputStream(uri)?.use { stream -> stream.write(export.bytes) }
                        } catch (exception: Exception) {
                            null
                        }
                    }
                if (written == null) {
                    container.backupUi.exportFailed()
                }
            }
        }

    val openLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                return@rememberLauncherForActivityResult
            }
            val stream =
                try {
                    context.contentResolver.openInputStream(uri)
                } catch (exception: Exception) {
                    null
                }
            if (stream == null) {
                container.backupUi.importFailed()
            } else {
                container.backupUi.startImport(stream)
            }
        }

    if (!visible) {
        return
    }
    LaunchedEffect(state.pendingExport) {
        val export = state.pendingExport
        if (export != null && pending == null) {
            pending = export
            saveLauncher.launch(BUNDLE_FILE_NAME)
        }
    }
    BackHandler { BackupScreenSession.hide() }
    BackupScreenContent(
        state = state,
        onExport = container.backupUi::export,
        onImport = { openLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
        onAnswer = { choice, applyToAll -> container.backupUi.answer(choice, applyToAll) },
        onClose = { BackupScreenSession.hide() },
        onReset = container.backupUi::reset,
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun BackupScreenContent(
    state: BackupUiState,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onAnswer: (BundleConflictChoice, Boolean) -> Unit,
    onClose: () -> Unit,
    onReset: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(SURFACE)) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(PADDING_DP.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = stringResource(R.string.backup_title),
                    style = TextStyle(color = INK, fontSize = TITLE_SP.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                val close = stringResource(R.string.backup_close)
                Box(
                    modifier =
                        Modifier
                            .size(BUTTON_HEIGHT_DP.dp)
                            .clickable(onClick = onClose)
                            .semantics { contentDescription = close },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        stringResource(R.string.backup_close_glyph),
                        style = TextStyle(color = MUTED, fontSize = 26.sp),
                    )
                }
            }
            BasicText(
                text = stringResource(R.string.backup_description),
                style = TextStyle(color = MUTED, fontSize = BODY_SP.sp),
                modifier = Modifier.padding(top = 8.dp),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
            ) {
                if (state.counts.isNotEmpty()) {
                    BasicText(
                        text = stringResource(R.string.backup_counts_title),
                        style = TextStyle(color = INK, fontSize = SMALL_SP.sp, fontWeight = FontWeight.Bold),
                    )
                    state.counts.forEach { count ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                text = kindLabel(count.kind),
                                style = TextStyle(color = MUTED, fontSize = BODY_SP.sp),
                                modifier = Modifier.weight(1f),
                            )
                            BasicText(
                                text = count.count.toString(),
                                style = TextStyle(color = INK, fontSize = BODY_SP.sp),
                            )
                        }
                    }
                }
                if (state.exportCounts.isNotEmpty()) {
                    val kinds = state.exportCounts.size
                    BasicText(
                        text = pluralStringResource(R.plurals.backup_ready, kinds, kinds, state.exportBytes),
                        style = TextStyle(color = ACCENT, fontSize = SMALL_SP.sp),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (state.phase == BackupPhase.WORKING) {
                    BasicText(
                        text = stringResource(R.string.backup_working),
                        style = TextStyle(color = MUTED, fontSize = SMALL_SP.sp),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                state.summary?.let { summary ->
                    BasicText(
                        text =
                            stringResource(
                                R.string.backup_import_done,
                                summary.added,
                                summary.replaced,
                                summary.kept,
                            ),
                        style = TextStyle(color = ACCENT, fontSize = SMALL_SP.sp),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    val warningKind = state.warnings.filterIsInstance<nl.r1a.grassmaps.core.services.backup.BackupWarning.UnknownKind>()
                    warningKind.forEach { warning ->
                        BasicText(
                            text = stringResource(R.string.backup_unknown_kind, warning.kind),
                            style = TextStyle(color = MUTED, fontSize = SMALL_SP.sp),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    val failed = summary.kinds.filter { kind -> kind.error != null }
                    failed.forEach { kind ->
                        BasicText(
                            text = stringResource(R.string.backup_kind_failed, kindLabel(kind.kind)),
                            style = TextStyle(color = DANGER, fontSize = SMALL_SP.sp),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Box(
                        modifier =
                            Modifier
                                .padding(top = 12.dp)
                                .clickable(onClick = onReset),
                    ) {
                        BasicText(
                            text = stringResource(R.string.backup_start_over),
                            style = TextStyle(color = ACCENT, fontSize = SMALL_SP.sp, fontWeight = FontWeight.Bold),
                        )
                    }
                }
                state.error?.let { error -> ErrorText(error) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(
                    label = stringResource(R.string.backup_export),
                    enabled = state.phase != BackupPhase.WORKING,
                    onClick = onExport,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    label = stringResource(R.string.backup_import),
                    enabled = state.phase != BackupPhase.WORKING,
                    onClick = onImport,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        state.currentConflict?.let { conflict ->
            ConflictCard(
                state = state,
                kind = kindLabel(conflict.kind),
                onAnswer = onAnswer,
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ConflictCard(
    state: BackupUiState,
    kind: String,
    onAnswer: (BundleConflictChoice, Boolean) -> Unit,
) {
    var applyAll by remember(state.currentConflict) { mutableStateOf(false) }
    var answered by remember(state.currentConflict) { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize().background(SCRIM).padding(PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(CARD_MAX_HEIGHT_DP.dp)
                    .floatingSurface(LocalShellColors.current, RectangleShape)
                    .background(CARD)
                    .padding(16.dp),
        ) {
            BasicText(
                text = stringResource(R.string.backup_conflict_title, state.conflictNumber, state.conflictTotal, kind),
                style = TextStyle(color = INK, fontSize = 17.sp, fontWeight = FontWeight.Bold),
            )
            ValueBlock(
                title = stringResource(R.string.backup_conflict_existing),
                text = state.existingText,
                modifier = Modifier.weight(1f),
            )
            ValueBlock(
                title = stringResource(R.string.backup_conflict_incoming),
                text = state.incomingText,
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { applyAll = !applyAll },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(20.dp)
                            .switchOutline(LocalShellColors.current, applyAll, RectangleShape)
                            .background(if (applyAll) ACCENT else CARD),
                    contentAlignment = Alignment.Center,
                ) {
                    if (applyAll) {
                        BasicText(
                            stringResource(R.string.backup_check_glyph),
                            style = TextStyle(color = CARD, fontSize = 13.sp),
                        )
                    }
                }
                BasicText(
                    text = stringResource(R.string.backup_conflict_apply_all, kind),
                    style = TextStyle(color = INK, fontSize = SMALL_SP.sp),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionButton(
                    label = stringResource(R.string.backup_conflict_keep),
                    enabled = !answered,
                    onClick = {
                        if (!answered) {
                            answered = true
                            onAnswer(BundleConflictChoice.EXISTING, applyAll)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    background = CARD,
                    foreground = INK,
                )
                ActionButton(
                    label = stringResource(R.string.backup_conflict_use),
                    enabled = !answered,
                    onClick = {
                        if (!answered) {
                            answered = true
                            onAnswer(BundleConflictChoice.INCOMING, applyAll)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ValueBlock(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 10.dp)) {
        BasicText(
            text = title,
            style = TextStyle(color = MUTED, fontSize = SMALL_SP.sp, fontWeight = FontWeight.Bold),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .background(SURFACE)
                    .fieldOutline(LocalShellColors.current, RectangleShape)
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            BasicText(
                text = text,
                style = TextStyle(color = INK, fontSize = MONO_SP.sp, fontFamily = FontFamily.Monospace),
                maxLines = 96,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = ACCENT,
    foreground: Color = LocalShellColors.current.onAccent,
) {
    Box(
        modifier =
            modifier
                .height(BUTTON_HEIGHT_DP.dp)
                .background(if (enabled) background else MUTED)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = foreground, fontSize = BODY_SP.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ErrorText(error: AppError) {
    val text =
        when (error) {
            is AppError.NewerSchema -> stringResource(R.string.backup_error_newer)
            AppError.CorruptData, AppError.Unsupported -> stringResource(R.string.backup_error_corrupt)
            AppError.PermissionDenied -> stringResource(R.string.backup_error_permission)
            AppError.Conflict -> stringResource(R.string.backup_error_conflict)
            else -> stringResource(R.string.backup_error_generic)
        }
    BasicText(
        text = text,
        style = TextStyle(color = DANGER, fontSize = SMALL_SP.sp),
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun kindLabel(kind: String): String =
    stringResource(
        when (kind) {
            nl.r1a.grassmaps.core.services.backup.BackupKinds.TRACKS -> R.string.backup_kind_tracks
            nl.r1a.grassmaps.core.services.backup.BackupKinds.FAVOURITES -> R.string.backup_kind_favourites
            nl.r1a.grassmaps.core.services.backup.BackupKinds.PROFILES -> R.string.backup_kind_profiles
            nl.r1a.grassmaps.core.services.backup.BackupKinds.ROUTING -> R.string.backup_kind_routing
            nl.r1a.grassmaps.core.services.backup.BackupKinds.ALERTS -> R.string.backup_kind_alerts
            nl.r1a.grassmaps.core.services.backup.BackupKinds.PRIVACY -> R.string.backup_kind_privacy
            nl.r1a.grassmaps.core.services.backup.BackupKinds.SEARCH_HISTORY -> R.string.backup_kind_search_history
            nl.r1a.grassmaps.core.services.backup.BackupKinds.ROUTE_HISTORY -> R.string.backup_kind_route_history
            nl.r1a.grassmaps.core.services.backup.BackupKinds.MAP_STYLE -> R.string.backup_kind_map_style
            nl.r1a.grassmaps.core.services.backup.BackupKinds.PLUGINS -> R.string.backup_kind_plugins
            else -> R.string.backup_kind_unknown
        },
    )

private const val BUNDLE_FILE_NAME = "grass-maps-backup.zip"
