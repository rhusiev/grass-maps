package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.api.model.RichText
import nl.r1a.grassmaps.plugin.api.model.RichTextStyle
import nl.r1a.grassmaps.plugin.api.model.TrackLibraryState
import nl.r1a.grassmaps.plugin.api.model.TrackSort
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellIcon
import nl.r1a.grassmaps.plugin.ui.api.ShellIcons
import nl.r1a.grassmaps.plugin.ui.api.chipOutline
import nl.r1a.grassmaps.plugin.ui.api.fieldOutline
import nl.r1a.grassmaps.plugin.ui.api.floatingSurface
import nl.r1a.grassmaps.plugin.ui.api.inkOn
import nl.r1a.grassmaps.plugin.ui.api.sheetSurface
import nl.r1a.grassmaps.plugin.ui.api.shellColors
import nl.r1a.grassmaps.plugin.ui.api.switchOutline

/**
 * The track library screen (T-0414). The plugin owns the state, the four fixed
 * orders, the search and the scoped undo; this shell draws the design's folder
 * tiles, list rows and action sheets and sends taps back. It never reads a
 * sidecar or a GPX document.
 *
 * The layout is round 8 `x-tracks`: read at the top, press at the bottom. The
 * header, the folder tiles and the read-only sort line sit above the rows; the
 * bottom bar holds search, sort and +, which creates a folder. A folder tile
 * filters and the pencil on the selected one renames it, because both act on
 * that tile. Pressing a track selects it, and the bar of the selected track
 * replaces the bottom bar with Move, Rename, Share, Save and Delete; Delete happens
 * at once and Undo reverses it, while Save hands the track to the system
 * document picker (T-0410). A link in a description shows its full address
 * before the host opens it in the system browser. The edit sheet also carries
 * the round-6 "Show on map" switch (T-0415): off stores the sidecar flag, the
 * map stops drawing the track, and the row says it is hidden.
 *
 * [dark] selects the light or dark tokens, so both themes are drawn from the
 * same layout. Device interaction is unexercised in CI; the content resolver
 * and the action wiring are JVM-tested.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun TrackLibraryHost(
    state: TrackLibraryState,
    strings: Strings,
    dark: Boolean,
    onClose: () -> Unit,
    onCreateFolder: (name: String) -> Unit,
    onRenameFolder: (folderId: String, name: String) -> Unit,
    onEditTrack: (trackId: String, name: String, description: String, folderId: String?, hidden: Boolean) -> Unit,
    onMoveTrack: (trackId: String, folderId: String?) -> Unit,
    onDeleteTrack: (trackId: String) -> Unit,
    onShare: (trackId: String) -> Unit,
    onSave: (trackId: String) -> Unit,
    onOpenLink: (String) -> Unit,
    onUndo: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (TrackSort) -> Unit,
    onSelectFolder: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = trackLibraryPalette(dark)
    val content = trackLibraryContent(state, strings)
    val selection = trackSelectionBarContent(strings)
    var sheet by remember { mutableStateOf(LibrarySheet.NONE) }
    var selectedTrackId by remember { mutableStateOf<String?>(null) }
    var linkToOpen by remember { mutableStateOf<String?>(null) }
    var folderSheetMode by remember { mutableStateOf(FolderSheetMode.CREATE) }
    var renameFolderId by remember { mutableStateOf<String?>(null) }
    var nameField by remember { mutableStateOf("") }
    var descriptionField by remember { mutableStateOf("") }
    var folderField by remember { mutableStateOf<String?>(null) }
    var showOnMapField by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf(state.browse.query) }
    val selectedRow = selectedLibraryRow(content.rows, selectedTrackId)
    val shell = shellColors(dark)

    LaunchedEffect(selectedTrackId, state.tracks.size) {
        if (sheet == LibrarySheet.EDIT || sheet == LibrarySheet.MOVE) {
            if (selectedRow == null) {
                sheet = LibrarySheet.NONE
                selectedTrackId = null
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(WEIGHT_ONE)
                        .verticalScroll(rememberScrollState())
                        .padding(start = SCREEN_PADDING_DP.dp, end = SCREEN_PADDING_DP.dp, top = SCREEN_TOP_DP.dp),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
            ) {
                Header(
                    content = content,
                    colors = colors,
                    shell = shell,
                    selectionTitle = selection.title.takeIf { selectedRow != null },
                    onClose = onClose,
                )
                FolderTiles(
                    content = content,
                    colors = colors,
                    onSelect = onSelectFolder,
                    onRename = { folderId ->
                        val folder = state.folders.firstOrNull { folder -> folder.id == folderId }
                        if (folder != null) {
                            renameFolderId = folder.id
                            nameField = folder.name
                            folderSheetMode = FolderSheetMode.RENAME
                            sheet = LibrarySheet.FOLDER
                        }
                    },
                )
                ListHeading(content = content, colors = colors)
                content.empty?.let { empty -> Line(text = empty, colors = colors, secondary = true) }
                content.rows.forEach { row ->
                    TrackRow(
                        row = row,
                        colors = colors,
                        selected = row.id == selectedTrackId,
                        onSelect = {
                            selectedTrackId = row.id
                            sheet = LibrarySheet.NONE
                        },
                        onLink = { url ->
                            linkToOpen = url
                            sheet = LibrarySheet.LINK
                        },
                    )
                }
                content.noMatches?.let { message -> Line(text = message, colors = colors, secondary = true) }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
            ) {
                if (content.undoMessage != null) {
                    UndoBar(message = content.undoMessage, undo = content.undo, colors = colors, onUndo = onUndo)
                }
                if (selectedRow != null) {
                    SelectionBar(
                        content = selection,
                        colors = colors,
                        shell = shell,
                        onClose = { selectedTrackId = null },
                        onAction = { kind ->
                            when (kind) {
                                TrackSelectionActionKind.MOVE -> {
                                    sheet = LibrarySheet.MOVE
                                }

                                TrackSelectionActionKind.RENAME -> {
                                    nameField = selectedRow.name
                                    descriptionField =
                                        state.tracks
                                            .firstOrNull { track -> track.id == selectedRow.id }
                                            ?.description
                                            .orEmpty()
                                    folderField = selectedRow.listId
                                    showOnMapField = !selectedRow.hidden
                                    sheet = LibrarySheet.EDIT
                                }

                                TrackSelectionActionKind.SHARE -> {
                                    selectedTrackId = null
                                    onShare(selectedRow.id)
                                }

                                TrackSelectionActionKind.SAVE -> {
                                    onSave(selectedRow.id)
                                }

                                TrackSelectionActionKind.DELETE -> {
                                    selectedTrackId = null
                                    onDeleteTrack(selectedRow.id)
                                }
                            }
                        },
                    )
                } else {
                    TrackLibraryBar(
                        content = content,
                        shell = shell,
                        query = query,
                        onQueryChange = {
                            query = it
                            onQueryChange(it)
                        },
                        onSort = { sheet = LibrarySheet.SORT },
                        onAdd = {
                            renameFolderId = null
                            nameField = ""
                            folderSheetMode = FolderSheetMode.CREATE
                            sheet = LibrarySheet.FOLDER
                        },
                    )
                }
            }
        }
        if (sheet != LibrarySheet.NONE) {
            Scrim(onClose = { sheet = LibrarySheet.NONE })
            when (sheet) {
                LibrarySheet.EDIT -> {
                    selectedRow?.let { row ->
                        EditSheet(
                            content = trackEditContent(row, state, strings),
                            colors = colors,
                            name = nameField,
                            description = descriptionField,
                            selectedFolderId = folderField,
                            showOnMap = showOnMapField,
                            onNameChange = { nameField = it },
                            onDescriptionChange = { descriptionField = it },
                            onFolderChange = { folderField = it },
                            onShowOnMapChange = { showOnMapField = it },
                            onSave = {
                                sheet = LibrarySheet.NONE
                                selectedTrackId = null
                                onEditTrack(row.id, nameField, descriptionField, folderField, !showOnMapField)
                            },
                            onCancel = { sheet = LibrarySheet.NONE },
                        )
                    }
                }

                LibrarySheet.MOVE -> {
                    selectedRow?.let { row ->
                        MoveSheet(
                            title = strings.resolve(MOVE_TITLE_KEY),
                            folders = trackEditContent(row, state, strings).folders,
                            cancel = strings.resolve(TRACK_LIBRARY_CANCEL_KEY),
                            colors = colors,
                            onMove = { folderId ->
                                sheet = LibrarySheet.NONE
                                selectedTrackId = null
                                onMoveTrack(row.id, folderId)
                            },
                            onCancel = { sheet = LibrarySheet.NONE },
                        )
                    }
                }

                LibrarySheet.FOLDER -> {
                    val title =
                        if (folderSheetMode == FolderSheetMode.RENAME) {
                            strings.resolve(RENAME_FOLDER_TITLE_KEY)
                        } else {
                            strings.resolve(CREATE_FOLDER_TITLE_KEY)
                        }
                    FolderSheet(
                        content = trackFolderSheetContent(title, nameField, strings),
                        colors = colors,
                        name = nameField,
                        onNameChange = { nameField = it },
                        onSave = {
                            val folderId = renameFolderId
                            val name = nameField.trim()
                            sheet = LibrarySheet.NONE
                            if (name.isNotEmpty()) {
                                if (folderSheetMode == FolderSheetMode.RENAME && folderId != null) {
                                    onRenameFolder(folderId, name)
                                } else {
                                    onCreateFolder(name)
                                }
                            }
                        },
                        onCancel = { sheet = LibrarySheet.NONE },
                    )
                }

                LibrarySheet.SORT -> {
                    SortSheet(
                        title = content.sort,
                        sorts = content.sorts,
                        cancel = strings.resolve(TRACK_LIBRARY_CANCEL_KEY),
                        colors = colors,
                        onChoose = { sort ->
                            sheet = LibrarySheet.NONE
                            onSortChange(sort)
                        },
                        onCancel = { sheet = LibrarySheet.NONE },
                    )
                }

                LibrarySheet.LINK -> {
                    val address = linkToOpen
                    if (address != null) {
                        LinkSheet(
                            content = trackLinkPromptContent(address, strings),
                            colors = colors,
                            onOpen = {
                                sheet = LibrarySheet.NONE
                                linkToOpen = null
                                onOpenLink(address)
                            },
                            onCancel = {
                                sheet = LibrarySheet.NONE
                                linkToOpen = null
                            },
                        )
                    }
                }

                LibrarySheet.NONE -> {
                    Unit
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Header(
    content: TrackLibraryContent,
    colors: TrackLibraryPalette,
    shell: ShellColors,
    selectionTitle: String?,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(colors = shell, icon = ShellIcons.ChevronLeft, label = content.close, filled = false, onClick = onClose)
        Column(modifier = Modifier.weight(WEIGHT_ONE)) {
            Line(text = selectionTitle ?: content.title, colors = colors, bold = true, size = TITLE_FONT_SP)
            Line(text = content.subtitle, colors = colors, secondary = true)
        }
    }
}

/**
 * The folder tiles in two columns, as round 8 `x-tracks` draws them. A tile
 * filters the list; the selected folder's pencil renames that folder.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun FolderTiles(
    content: TrackLibraryContent,
    colors: TrackLibraryPalette,
    onSelect: (String?) -> Unit,
    onRename: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp)) {
        content.folders.chunked(TILE_COLUMNS).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp),
            ) {
                pair.forEach { folder ->
                    FolderTile(
                        folder = folder,
                        renameLabel = content.renameFolder,
                        colors = colors,
                        onSelect = onSelect,
                        onRename = onRename,
                        modifier = Modifier.weight(WEIGHT_ONE).fillMaxHeight(),
                    )
                }
                if (pair.size < TILE_COLUMNS) {
                    Box(modifier = Modifier.weight(WEIGHT_ONE))
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun FolderTile(
    folder: TrackFolderRow,
    renameLabel: String,
    colors: TrackLibraryPalette,
    onSelect: (String?) -> Unit,
    onRename: (String) -> Unit,
    modifier: Modifier,
) {
    val ink = if (folder.selected) colors.selectedText else colors.textPrimary
    Row(
        modifier =
            modifier
                .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                .background(
                    if (folder.selected) colors.selectedSurface else colors.field,
                    RoundedCornerShape(TILE_CORNER_DP.dp),
                ).selectable(selected = folder.selected, role = Role.Tab) { onSelect(folder.id) }
                .padding(TILE_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
    ) {
        ShellIcon(icon = ShellIcons.Folder, tint = ink, modifier = Modifier.size(TILE_ICON_DP.dp))
        Column(modifier = Modifier.weight(WEIGHT_ONE)) {
            Line(text = folder.name, colors = colors, bold = true, colour = ink)
            Line(text = folder.summary, colors = colors, size = NOTE_FONT_SP, colour = if (folder.selected) ink else colors.textSecondary)
        }
        val folderId = folder.id
        if (folderId != null && folder.selected) {
            Box(
                modifier =
                    Modifier
                        .size(MapControlMetrics.TAP_TARGET_DP.dp)
                        .clickable(role = Role.Button) { onRename(folderId) }
                        .semantics { contentDescription = renameLabel },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(text = RENAME_GLYPH, style = TextStyle(color = ink, fontSize = ROW_FONT_SP.sp))
            }
        }
    }
}

/**
 * The read-only lines above the rows: the shown folder, the order in use and
 * the folder's totals. Sorting itself is in the bottom bar.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun ListHeading(
    content: TrackLibraryContent,
    colors: TrackLibraryPalette,
) {
    Row(
        modifier = Modifier.padding(top = ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ACTION_PADDING_DP.dp),
    ) {
        ShellIcon(icon = ShellIcons.Folder, tint = colors.textSecondary, modifier = Modifier.size(INFO_ICON_DP.dp))
        Line(text = content.listTitle.uppercase(), colors = colors, secondary = true, bold = true, size = NOTE_FONT_SP)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ACTION_PADDING_DP.dp)) {
        ShellIcon(icon = ShellIcons.Sort, tint = colors.textSecondary, modifier = Modifier.size(INFO_ICON_DP.dp))
        BasicText(
            text = content.sortSummary,
            style = TextStyle(color = colors.textSecondary, fontSize = NOTE_FONT_SP.sp),
            modifier = Modifier.weight(WEIGHT_ONE),
        )
        BasicText(
            text = content.listSummary,
            style = TextStyle(color = colors.textSecondary, fontSize = NOTE_FONT_SP.sp, textAlign = TextAlign.End),
            modifier = Modifier.weight(WEIGHT_ONE),
        )
    }
}

/** The library's bottom bar: search, sort and + (a new folder), from the content's bar actions. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun TrackLibraryBar(
    content: TrackLibraryContent,
    shell: ShellColors,
    query: String,
    onQueryChange: (String) -> Unit,
    onSort: () -> Unit,
    onAdd: () -> Unit,
) {
    val search = content.barActions.firstOrNull { it.action == TrackBarAction.SEARCH } ?: return
    val buttons =
        content.barActions.mapNotNull { action ->
            when (action.action) {
                TrackBarAction.SEARCH -> null
                TrackBarAction.SORT -> ListBarButton(ShellIcons.Sort, action.label, onClick = onSort)
                TrackBarAction.ADD -> ListBarButton(ShellIcons.Plus, action.label, onClick = onAdd)
            }
        }
    ListBottomBar(
        colors = shell,
        placeholder = search.label,
        query = query,
        onQueryChange = onQueryChange,
        buttons = buttons,
        modifier = Modifier.padding(horizontal = BAR_MARGIN_DP.dp, vertical = ROW_GAP_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun TrackRow(
    row: TrackLibraryRowContent,
    colors: TrackLibraryPalette,
    selected: Boolean,
    onSelect: () -> Unit,
    onLink: (String) -> Unit,
) {
    val linkActions =
        row.linkActions.map { link ->
            CustomAccessibilityAction(link.label) {
                onLink(link.href)
                true
            }
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                .background(
                    if (selected) colors.selection else Color.Transparent,
                    RoundedCornerShape(TILE_CORNER_DP.dp),
                ).combinedClickable(
                    role = Role.Button,
                    onClick = onSelect,
                    onLongClick = onSelect,
                ).semantics {
                    this.selected = selected
                    if (linkActions.isNotEmpty()) {
                        customActions = linkActions
                    }
                }.padding(TILE_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(DOT_SIZE_DP.dp)
                    .background(row.colour?.let { argb -> Color(argb) } ?: colors.textSecondary, CircleShape),
        )
        Column(modifier = Modifier.weight(WEIGHT_ONE)) {
            Line(text = row.name, colors = colors, bold = true)
            if (row.meta.isNotEmpty()) {
                Line(text = row.meta, colors = colors, secondary = true)
            }
            row.listName?.let { list -> Line(text = list, colors = colors, secondary = true) }
            row.hiddenNotice?.let { notice -> Line(text = notice, colors = colors, colour = ROUTE_CHANGED_COLOR) }
            row.description?.let { description ->
                FormattedDescription(text = description, colors = colors, onLink = onLink)
            }
        }
    }
}

/**
 * A description with the sanitised bold, italic and link runs. A link tap asks
 * the host for the full address before it opens the system browser.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun FormattedDescription(
    text: RichText,
    colors: TrackLibraryPalette,
    onLink: (String) -> Unit,
) {
    val annotated = remember(text, colors.accent) { text.toAnnotatedString(colors.accent) }
    var layout by remember(annotated) { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(
        text = annotated,
        style = TextStyle(color = colors.textSecondary, fontSize = ROW_FONT_SP.sp),
        onTextLayout = { result -> layout = result },
        modifier =
            Modifier.pointerInput(annotated, text) {
                detectTapGestures { position ->
                    val result = layout ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(position)
                    text.linkAt(offset)?.let(onLink)
                }
            },
    )
}

/** The safe address whose run holds [offset], or null for ordinary text. */
private fun RichText.linkAt(offset: Int): String? =
    spans
        .firstOrNull { span ->
            span.style == RichTextStyle.LINK && offset >= span.start && offset < span.end
        }?.href

/** Builds the Compose text from the safe model; only kept runs can carry a style. */
private fun RichText.toAnnotatedString(linkColour: Color): AnnotatedString =
    buildAnnotatedString {
        append(text)
        spans.forEach { span ->
            when (span.style) {
                RichTextStyle.BOLD -> {
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), span.start, span.end)
                }

                RichTextStyle.ITALIC -> {
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), span.start, span.end)
                }

                RichTextStyle.LINK -> {
                    addStyle(
                        SpanStyle(color = linkColour, textDecoration = TextDecoration.Underline),
                        span.start,
                        span.end,
                    )
                }
            }
        }
    }

@Suppress("ktlint:standard:function-naming")
@Composable
private fun UndoBar(
    message: String,
    undo: String,
    colors: TrackLibraryPalette,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .padding(horizontal = SCREEN_PADDING_DP.dp)
                .widthIn(max = SHEET_MAX_WIDTH_DP.dp)
                .background(colors.undoBackground, RoundedCornerShape(UNDO_CORNER_DP.dp))
                .padding(FIELD_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = message,
            style = TextStyle(color = colors.undoText, fontSize = ROW_FONT_SP.sp),
            modifier = Modifier.weight(WEIGHT_ONE, fill = false),
        )
        ListActionButton(
            text = undo,
            background = Color.Transparent,
            ink = colors.undoAction,
            onClick = onUndo,
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Scrim(onClose: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(LocalShellColors.current.scrim)
                .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
    )
}

/**
 * The bar of the selected track: a close control that leaves the selection and
 * the five fixed actions Move, Rename, Share, Save and Delete. Delete happens at
 * once and the library's Undo bar reverses it; Save opens the system picker. It
 * takes the bottom bar's place while a track is selected, and its actions wrap
 * onto a second line at large font scales instead of clipping.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("ktlint:standard:function-naming")
@Composable
private fun SelectionBar(
    content: TrackSelectionBarContent,
    colors: TrackLibraryPalette,
    shell: ShellColors,
    onClose: () -> Unit,
    onAction: (TrackSelectionActionKind) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .padding(horizontal = BAR_MARGIN_DP.dp, vertical = ROW_GAP_DP.dp)
                .fillMaxWidth()
                .floatingSurface(shell, RoundedCornerShape(SELECTION_CORNER_DP.dp))
                .background(colors.surface, RoundedCornerShape(SELECTION_CORNER_DP.dp))
                .padding(SELECTION_BAR_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(colors = shell, icon = ShellIcons.Close, label = content.close, onClick = onClose)
        FlowRow(
            modifier = Modifier.weight(WEIGHT_ONE),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            content.actions.forEach { action ->
                Box(
                    modifier =
                        Modifier
                            .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                            .widthIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                            .clickable(role = Role.Button) { onAction(action.kind) }
                            .padding(horizontal = ACTION_PADDING_DP.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = action.label,
                        style =
                            TextStyle(
                                color = if (action.kind == TrackSelectionActionKind.DELETE) colors.danger else colors.textPrimary,
                                fontSize = ROW_FONT_SP.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            ),
                    )
                }
            }
        }
    }
}

/** The sort choices of the library, the current one checked. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun SortSheet(
    title: String,
    sorts: List<TrackSortRow>,
    cancel: String,
    colors: TrackLibraryPalette,
    onChoose: (TrackSort) -> Unit,
    onCancel: () -> Unit,
) {
    SheetFrame(colors = colors) {
        Line(text = title, colors = colors, bold = true, size = TITLE_FONT_SP)
        sorts.forEach { row ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                        .selectable(selected = row.selected, role = Role.RadioButton) { onChoose(row.sort) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = row.label,
                    style =
                        TextStyle(
                            color = colors.textPrimary,
                            fontSize = ROW_FONT_SP.sp,
                            fontWeight = if (row.selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    modifier = Modifier.weight(WEIGHT_ONE),
                )
                if (row.selected) {
                    ShellIcon(icon = ShellIcons.Check, tint = colors.accent, modifier = Modifier.size(TILE_ICON_DP.dp))
                }
            }
        }
        ListActionButton(
            text = cancel,
            background = Color.Transparent,
            ink = colors.textSecondary,
            onClick = onCancel,
            fill = true,
        )
    }
}

/** The confirmation that shows a link's full address before the browser opens it. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun LinkSheet(
    content: TrackLinkPromptContent,
    colors: TrackLibraryPalette,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    SheetFrame(colors = colors) {
        Line(text = content.title, colors = colors, bold = true, size = TITLE_FONT_SP)
        Line(text = content.address, colors = colors, secondary = true)
        Row(horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
            ListActionButton(
                text = content.cancel,
                background = colors.field,
                ink = colors.textPrimary,
                onClick = onCancel,
            )
            ListActionButton(
                text = content.open,
                background = colors.selectedSurface,
                ink = colors.selectedText,
                onClick = onOpen,
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun EditSheet(
    content: TrackEditContent,
    colors: TrackLibraryPalette,
    name: String,
    description: String,
    selectedFolderId: String?,
    showOnMap: Boolean,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onFolderChange: (String?) -> Unit,
    onShowOnMapChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    SheetFrame(colors = colors) {
        Line(text = content.title, colors = colors, bold = true, size = TITLE_FONT_SP)
        Line(text = content.nameLabel, colors = colors, secondary = true)
        SheetTextField(value = name, colors = colors, onChange = onNameChange)
        Line(text = content.folderLabel, colors = colors, secondary = true)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(CHIP_GAP_DP.dp),
        ) {
            content.folders.forEach { folder ->
                Chip(
                    label = folder.name,
                    selected = folder.id == selectedFolderId,
                    colors = colors,
                    onClick = { onFolderChange(folder.id) },
                )
            }
        }
        Line(text = content.descriptionLabel, colors = colors, secondary = true)
        BasicTextField(
            value = description,
            onValueChange = onDescriptionChange,
            textStyle = TextStyle(color = colors.textPrimary, fontSize = ROW_FONT_SP.sp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(DESCRIPTION_HEIGHT_DP.dp)
                    .background(colors.surface, RoundedCornerShape(FIELD_CORNER_DP.dp))
                    .fieldOutline(colors.tokens, RoundedCornerShape(FIELD_CORNER_DP.dp))
                    .padding(FIELD_PADDING_DP.dp),
        )
        Line(text = content.descriptionNote, colors = colors, secondary = true)
        VisibilityRow(
            label = content.visibilityLabel,
            note = content.visibilityNote,
            checked = showOnMap,
            colors = colors,
            onChange = onShowOnMapChange,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
            ListActionButton(
                text = content.cancel,
                background = colors.field,
                ink = colors.textPrimary,
                onClick = onCancel,
            )
            ListActionButton(
                text = content.save,
                background = colors.selectedSurface,
                ink = colors.selectedText,
                onClick = onSave,
            )
        }
    }
}

/**
 * The edit sheet's map visibility row (T-0415): the round-6 t-looks wording, a
 * detail line and a switch. The switch is "Show on map", so on means drawn; the
 * value is a draft like the name and description, and only the sheet's Save
 * writes the inverse into the sidecar's hidden flag.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun VisibilityRow(
    label: String,
    note: String,
    checked: Boolean,
    colors: TrackLibraryPalette,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch) { onChange(it) }
                .padding(vertical = ACTION_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
    ) {
        Column(modifier = Modifier.weight(WEIGHT_ONE)) {
            Line(text = label, colors = colors)
            Line(text = note, colors = colors, secondary = true, size = NOTE_FONT_SP)
        }
        VisibilitySwitch(checked = checked, colors = colors)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun VisibilitySwitch(
    checked: Boolean,
    colors: TrackLibraryPalette,
) {
    Box(
        modifier =
            Modifier
                .width(SWITCH_WIDTH_DP.dp)
                .height(SWITCH_HEIGHT_DP.dp)
                .background(
                    if (checked) colors.accent else colors.field,
                    RoundedCornerShape(SWITCH_HEIGHT_DP.dp),
                ).switchOutline(colors.tokens, checked, RoundedCornerShape(SWITCH_HEIGHT_DP.dp))
                .padding(horizontal = SWITCH_INSET_DP.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier =
                Modifier
                    .size(SWITCH_KNOB_DP.dp)
                    .background(if (checked) colors.selectedText else colors.textSecondary, RoundedCornerShape(SWITCH_KNOB_DP.dp)),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun MoveSheet(
    title: String,
    folders: List<TrackFolderChoice>,
    cancel: String,
    colors: TrackLibraryPalette,
    onMove: (String?) -> Unit,
    onCancel: () -> Unit,
) {
    SheetFrame(colors = colors) {
        Line(text = title, colors = colors, bold = true, size = TITLE_FONT_SP)
        folders.forEach { folder ->
            BasicText(
                text = folder.name,
                style =
                    TextStyle(
                        color = if (folder.selected) colors.accent else colors.textPrimary,
                        fontSize = ROW_FONT_SP.sp,
                        fontWeight = if (folder.selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                        .clickable(role = Role.Button) { onMove(folder.id) }
                        .padding(SHEET_ROW_PADDING_DP.dp),
            )
        }
        ListActionButton(
            text = cancel,
            background = Color.Transparent,
            ink = colors.textSecondary,
            onClick = onCancel,
            fill = true,
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun FolderSheet(
    content: TrackFolderSheetContent,
    colors: TrackLibraryPalette,
    name: String,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    SheetFrame(colors = colors) {
        Line(text = content.title, colors = colors, bold = true, size = TITLE_FONT_SP)
        Line(text = content.nameLabel, colors = colors, secondary = true)
        SheetTextField(value = name, colors = colors, onChange = onNameChange)
        Row(horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
            ListActionButton(
                text = content.cancel,
                background = colors.field,
                ink = colors.textPrimary,
                onClick = onCancel,
            )
            ListActionButton(
                text = content.save,
                background = colors.selectedSurface,
                ink = colors.selectedText,
                onClick = onSave,
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SheetFrame(
    colors: TrackLibraryPalette,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier =
                Modifier
                    .padding(SCREEN_PADDING_DP.dp)
                    .widthIn(max = SHEET_MAX_WIDTH_DP.dp)
                    .sheetSurface(RoundedCornerShape(SHEET_CORNER_DP.dp))
                    .background(colors.surface, RoundedCornerShape(SHEET_CORNER_DP.dp))
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .padding(SHEET_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
            content = content,
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SheetTextField(
    value: String,
    colors: TrackLibraryPalette,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.textPrimary, fontSize = ROW_FONT_SP.sp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.field, RoundedCornerShape(FIELD_CORNER_DP.dp))
                .fieldOutline(colors.tokens, RoundedCornerShape(FIELD_CORNER_DP.dp))
                .padding(FIELD_PADDING_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    colors: TrackLibraryPalette,
    onClick: () -> Unit,
) {
    BasicText(
        text = label,
        style =
            TextStyle(
                color = if (selected) colors.selectedText else colors.textPrimary,
                fontSize = ROW_FONT_SP.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
        modifier =
            Modifier
                .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                .background(
                    if (selected) colors.selectedSurface else colors.field,
                    RoundedCornerShape(CHIP_CORNER_DP.dp),
                ).chipOutline(
                    colors.tokens,
                    RoundedCornerShape(CHIP_CORNER_DP.dp),
                    selection = if (selected) colors.selectedSurface else null,
                ).clickable(onClick = onClick)
                .padding(CHIP_PADDING_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Line(
    text: String,
    colors: TrackLibraryPalette,
    secondary: Boolean = false,
    bold: Boolean = false,
    size: Int = ROW_FONT_SP,
    colour: Color? = null,
) {
    BasicText(
        text = text,
        style =
            TextStyle(
                color = colour ?: if (secondary) colors.textSecondary else colors.textPrimary,
                fontSize = size.sp,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            ),
    )
}

/** The light and dark tokens of the library screen. */
internal data class TrackLibraryPalette(
    val background: Color,
    val surface: Color,
    val field: Color,
    val selectedSurface: Color,
    val selection: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val selectedText: Color,
    val accent: Color,
    val danger: Color,
    val undoBackground: Color,
    val undoText: Color,
    val undoAction: Color,
    /** The shell tokens, for the shared outline modifiers. */
    val tokens: ShellColors,
)

internal fun trackLibraryPalette(dark: Boolean): TrackLibraryPalette {
    val tokens = shellColors(dark)
    return TrackLibraryPalette(
        background = listScreenBackground(tokens),
        surface = tokens.surface,
        field = tokens.soft,
        selectedSurface = tokens.selectedChip,
        selection = tokens.accent.copy(alpha = if (dark) SELECTION_DARK_ALPHA else SELECTION_LIGHT_ALPHA),
        textPrimary = tokens.ink,
        textSecondary = tokens.muted,
        selectedText = inkOn(tokens.selectedChip, tokens),
        accent = tokens.accent,
        danger = tokens.danger,
        undoBackground = tokens.snackbarBackground,
        undoText = inkOn(tokens.snackbarBackground, tokens),
        undoAction = tokens.snackbarAction,
        tokens = tokens,
    )
}

private const val SELECTION_LIGHT_ALPHA = 0.12f
private const val SELECTION_DARK_ALPHA = 0.2f

private enum class LibrarySheet {
    NONE,
    EDIT,
    MOVE,
    FOLDER,
    SORT,
    LINK,
}

private enum class FolderSheetMode {
    CREATE,
    RENAME,
}

private const val MOVE_TITLE_KEY = "track_library_action_move"
private const val RENAME_FOLDER_TITLE_KEY = "track_library_rename_folder_title"
private const val CREATE_FOLDER_TITLE_KEY = "track_library_create_folder_title"

private const val RENAME_GLYPH = "✎"

private const val SCREEN_PADDING_DP = 16
private const val SCREEN_TOP_DP = 20
private const val SHEET_MAX_WIDTH_DP = 560
private const val SHEET_CORNER_DP = 22
private const val SHEET_PADDING_DP = 16
private const val SHEET_ROW_PADDING_DP = 14
private const val ROW_GAP_DP = 8
private const val TILE_GAP_DP = 8
private const val TILE_CORNER_DP = 14
private const val TILE_PADDING_DP = 12
private const val CHIP_GAP_DP = 6
private const val CHIP_CORNER_DP = 12
private const val CHIP_PADDING_DP = 14
private const val FIELD_CORNER_DP = 10
private const val FIELD_PADDING_DP = 10
private const val UNDO_CORNER_DP = 12
private const val DOT_SIZE_DP = 12
private const val DESCRIPTION_HEIGHT_DP = 120
private const val SELECTION_BAR_PADDING_DP = 5
private const val SELECTION_CORNER_DP = 29
private const val BAR_MARGIN_DP = 12
private const val TILE_COLUMNS = 2
private const val TILE_ICON_DP = 22
private const val INFO_ICON_DP = 16
private const val ACTION_PADDING_DP = 6
private const val TITLE_FONT_SP = 21
private const val ROW_FONT_SP = 15
private const val NOTE_FONT_SP = 13
private const val WEIGHT_ONE = 1f
private const val SWITCH_WIDTH_DP = 44
private const val SWITCH_HEIGHT_DP = 26
private const val SWITCH_KNOB_DP = 20
private const val SWITCH_INSET_DP = 3
