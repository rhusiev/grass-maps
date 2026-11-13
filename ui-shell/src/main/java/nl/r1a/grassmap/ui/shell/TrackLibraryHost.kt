package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.model.TrackLibraryState
import nl.r1a.grassmap.plugin.api.model.TrackSort
import nl.r1a.grassmap.plugin.api.service.Strings

/**
 * The track library screen (T-0414). The plugin owns the state, the four fixed
 * orders, the search and the scoped undo; this shell draws the design's folder
 * tiles, list rows and action sheets and sends taps back. It never reads a
 * sidecar or a GPX document.
 *
 * Every control is reachable from the screen itself: a folder tile filters, the
 * pencil renames, the new-folder tile creates, the sort chips reorder, the
 * search field matches names, and a row's action sheet edits, moves, shares or
 * deletes the track. Undo sits on the snackbar after a change.
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
    onEditTrack: (trackId: String, name: String, description: String, folderId: String?) -> Unit,
    onMoveTrack: (trackId: String, folderId: String?) -> Unit,
    onDeleteTrack: (trackId: String) -> Unit,
    onShare: (trackId: String) -> Unit,
    onUndo: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (TrackSort) -> Unit,
    onSelectFolder: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = trackLibraryPalette(dark)
    val content = trackLibraryContent(state, strings)
    var sheet by remember { mutableStateOf(LibrarySheet.NONE) }
    var selectedTrackId by remember { mutableStateOf<String?>(null) }
    var folderSheetMode by remember { mutableStateOf(FolderSheetMode.CREATE) }
    var renameFolderId by remember { mutableStateOf<String?>(null) }
    var nameField by remember { mutableStateOf("") }
    var descriptionField by remember { mutableStateOf("") }
    var folderField by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf(state.browse.query) }
    val selectedRow =
        content.rows.firstOrNull { row -> row.id == selectedTrackId }
            ?: content.rows.firstOrNull { row -> state.tracks.any { track -> track.id == selectedTrackId } }

    LaunchedEffect(selectedTrackId, state.tracks.size) {
        if (sheet == LibrarySheet.ACTIONS || sheet == LibrarySheet.EDIT || sheet == LibrarySheet.MOVE) {
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
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = SCREEN_PADDING_DP.dp,
                        end = SCREEN_PADDING_DP.dp,
                        top = SCREEN_TOP_DP.dp,
                        bottom = if (content.undoMessage != null) UNDO_RESERVED_DP.dp else 0.dp,
                    ),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        ) {
            Header(content = content, colors = colors, onClose = onClose)
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
                onNewFolder = {
                    renameFolderId = null
                    nameField = ""
                    folderSheetMode = FolderSheetMode.CREATE
                    sheet = LibrarySheet.FOLDER
                },
            )
            SectionHeader(content = content, colors = colors, onSortChange = onSortChange)
            SearchField(
                content = content,
                colors = colors,
                query = query,
                onQueryChange = {
                    query = it
                    onQueryChange(it)
                },
            )
            content.empty?.let { empty -> Line(text = empty, colors = colors, secondary = true) }
            content.rows.forEach { row ->
                TrackRow(
                    row = row,
                    colors = colors,
                    onOpen = {
                        selectedTrackId = row.id
                        sheet = LibrarySheet.ACTIONS
                    },
                )
            }
            content.noMatches?.let { message -> Line(text = message, colors = colors, secondary = true) }
        }
        if (content.undoMessage != null) {
            UndoBar(
                message = content.undoMessage,
                undo = content.undo,
                colors = colors,
                onUndo = onUndo,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (sheet != LibrarySheet.NONE) {
            Scrim(onClose = { sheet = LibrarySheet.NONE })
            when (sheet) {
                LibrarySheet.ACTIONS -> {
                    selectedRow?.let { row ->
                        ActionSheet(
                            content = trackActionSheetContent(row, strings),
                            colors = colors,
                            onAction = { kind ->
                                when (kind) {
                                    TrackActionKind.EDIT -> {
                                        nameField = row.name
                                        descriptionField =
                                            state.tracks
                                                .firstOrNull { track -> track.id == row.id }
                                                ?.description
                                                .orEmpty()
                                        folderField = row.listId
                                        sheet = LibrarySheet.EDIT
                                    }

                                    TrackActionKind.MOVE -> {
                                        sheet = LibrarySheet.MOVE
                                    }

                                    TrackActionKind.SHARE -> {
                                        sheet = LibrarySheet.NONE
                                        onShare(row.id)
                                    }

                                    TrackActionKind.DELETE -> {
                                        sheet = LibrarySheet.NONE
                                        selectedTrackId = null
                                        onDeleteTrack(row.id)
                                    }
                                }
                            },
                            onCancel = { sheet = LibrarySheet.NONE },
                        )
                    }
                }

                LibrarySheet.EDIT -> {
                    selectedRow?.let { row ->
                        EditSheet(
                            content = trackEditContent(row, state, strings),
                            colors = colors,
                            name = nameField,
                            description = descriptionField,
                            selectedFolderId = folderField,
                            onNameChange = { nameField = it },
                            onDescriptionChange = { descriptionField = it },
                            onFolderChange = { folderField = it },
                            onSave = {
                                sheet = LibrarySheet.NONE
                                selectedTrackId = null
                                onEditTrack(row.id, nameField, descriptionField, folderField)
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
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = BACK_GLYPH,
            style = TextStyle(color = colors.textPrimary, fontSize = TITLE_FONT_SP.sp),
            modifier =
                Modifier
                    .clickable(onClick = onClose)
                    .semantics { contentDescription = content.close }
                    .padding(ACTION_PADDING_DP.dp),
        )
        Column(modifier = Modifier.weight(WEIGHT_ONE)) {
            Line(text = content.title, colors = colors, bold = true, size = TITLE_FONT_SP)
            Line(text = content.subtitle, colors = colors, secondary = true)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun FolderTiles(
    content: TrackLibraryContent,
    colors: TrackLibraryPalette,
    onSelect: (String?) -> Unit,
    onRename: (String) -> Unit,
    onNewFolder: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp),
    ) {
        content.folders.forEach { folder ->
            Column(
                modifier =
                    Modifier
                        .background(
                            if (folder.selected) colors.selectedSurface else colors.surface,
                            RoundedCornerShape(TILE_CORNER_DP.dp),
                        ).clickable { onSelect(folder.id) }
                        .padding(TILE_PADDING_DP.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(ACTION_PADDING_DP.dp), verticalAlignment = Alignment.CenterVertically) {
                    Line(
                        text = folder.name,
                        colors = colors,
                        bold = true,
                        colour = if (folder.selected) colors.selectedText else colors.textPrimary,
                    )
                    if (folder.id != null && folder.selected) {
                        BasicText(
                            text = RENAME_GLYPH,
                            style = TextStyle(color = colors.selectedText, fontSize = ROW_FONT_SP.sp),
                            modifier =
                                Modifier
                                    .clickable { folder.id?.let(onRename) }
                                    .semantics { contentDescription = content.renameFolder }
                                    .padding(ACTION_PADDING_DP.dp),
                        )
                    }
                }
                Line(
                    text = folder.summary,
                    colors = colors,
                    secondary = !folder.selected,
                    colour = if (folder.selected) colors.selectedText else colors.textSecondary,
                )
            }
        }
        Column(
            modifier =
                Modifier
                    .background(colors.surface, RoundedCornerShape(TILE_CORNER_DP.dp))
                    .clickable(onClick = onNewFolder)
                    .padding(TILE_PADDING_DP.dp),
        ) {
            Line(text = content.newFolder, colors = colors, bold = true)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SectionHeader(
    content: TrackLibraryContent,
    colors: TrackLibraryPalette,
    onSortChange: (TrackSort) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(WEIGHT_ONE)) {
            Line(text = content.listTitle, colors = colors, bold = true)
            Line(text = content.listSummary, colors = colors, secondary = true)
        }
    }
    Line(text = content.sort, colors = colors, secondary = true)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP_DP.dp),
    ) {
        content.sorts.forEach { row ->
            BasicText(
                text = row.label,
                style =
                    TextStyle(
                        color = if (row.selected) colors.selectedText else colors.textSecondary,
                        fontSize = ROW_FONT_SP.sp,
                        fontWeight = if (row.selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                modifier =
                    Modifier
                        .background(
                            if (row.selected) colors.selectedSurface else colors.surface,
                            RoundedCornerShape(CHIP_CORNER_DP.dp),
                        ).clickable { onSortChange(row.sort) }
                        .padding(CHIP_PADDING_DP.dp),
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SearchField(
    content: TrackLibraryContent,
    colors: TrackLibraryPalette,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Line(text = content.search, colors = colors, secondary = true)
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.textPrimary, fontSize = ROW_FONT_SP.sp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(FIELD_CORNER_DP.dp))
                .padding(FIELD_PADDING_DP.dp),
    )
    if (query.isEmpty()) {
        Line(text = content.searchHint, colors = colors, secondary = true)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun TrackRow(
    row: TrackLibraryRowContent,
    colors: TrackLibraryPalette,
    onOpen: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(TILE_CORNER_DP.dp))
                .clickable(onClick = onOpen)
                .padding(TILE_PADDING_DP.dp),
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
            row.description?.let { description -> Line(text = description, colors = colors, secondary = true) }
        }
        BasicText(
            text = MORE_GLYPH,
            style = TextStyle(color = colors.textPrimary, fontSize = ROW_FONT_SP.sp),
            modifier = Modifier.padding(ACTION_PADDING_DP.dp),
        )
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
                .padding(SCREEN_PADDING_DP.dp)
                .widthIn(max = SHEET_MAX_WIDTH_DP.dp)
                .background(colors.undoBackground, RoundedCornerShape(UNDO_CORNER_DP.dp))
                .padding(FIELD_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Line(text = message, colors = colors, colour = colors.undoText)
        BasicText(
            text = undo,
            style = TextStyle(color = colors.accent, fontSize = ROW_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clickable(onClick = onUndo).padding(ACTION_PADDING_DP.dp),
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
                .background(SCRIM)
                .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ActionSheet(
    content: TrackActionSheetContent,
    colors: TrackLibraryPalette,
    onAction: (TrackActionKind) -> Unit,
    onCancel: () -> Unit,
) {
    SheetFrame(colors = colors) {
        Line(text = content.title, colors = colors, bold = true, size = TITLE_FONT_SP)
        Line(text = content.subtitle, colors = colors, secondary = true)
        content.actions.forEach { action ->
            BasicText(
                text = action.label,
                style =
                    TextStyle(
                        color = if (action.kind == TrackActionKind.DELETE) colors.danger else colors.textPrimary,
                        fontSize = ROW_FONT_SP.sp,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onAction(action.kind) }
                        .padding(SHEET_ROW_PADDING_DP.dp),
            )
        }
        CancelButton(text = content.cancel, colors = colors, onClick = onCancel)
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
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onFolderChange: (String?) -> Unit,
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
                    .padding(FIELD_PADDING_DP.dp),
        )
        Line(text = content.descriptionNote, colors = colors, secondary = true)
        Row(horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
            SecondaryButton(text = content.cancel, colors = colors, onClick = onCancel)
            PrimaryButton(text = content.save, colors = colors, onClick = onSave)
        }
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
                        .clickable { onMove(folder.id) }
                        .padding(SHEET_ROW_PADDING_DP.dp),
            )
        }
        CancelButton(text = cancel, colors = colors, onClick = onCancel)
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
            SecondaryButton(text = content.cancel, colors = colors, onClick = onCancel)
            PrimaryButton(text = content.save, colors = colors, onClick = onSave)
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
                .background(
                    if (selected) colors.selectedSurface else colors.field,
                    RoundedCornerShape(CHIP_CORNER_DP.dp),
                ).clickable(onClick = onClick)
                .padding(CHIP_PADDING_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PrimaryButton(
    text: String,
    colors: TrackLibraryPalette,
    onClick: () -> Unit,
) {
    BasicText(
        text = text,
        style = TextStyle(color = colors.selectedText, fontSize = ROW_FONT_SP.sp, fontWeight = FontWeight.Bold),
        modifier =
            Modifier
                .background(colors.selectedSurface, RoundedCornerShape(BUTTON_CORNER_DP.dp))
                .clickable(onClick = onClick)
                .padding(BUTTON_PADDING_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SecondaryButton(
    text: String,
    colors: TrackLibraryPalette,
    onClick: () -> Unit,
) {
    BasicText(
        text = text,
        style = TextStyle(color = colors.textPrimary, fontSize = ROW_FONT_SP.sp),
        modifier =
            Modifier
                .background(colors.field, RoundedCornerShape(BUTTON_CORNER_DP.dp))
                .clickable(onClick = onClick)
                .padding(BUTTON_PADDING_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun CancelButton(
    text: String,
    colors: TrackLibraryPalette,
    onClick: () -> Unit,
) {
    BasicText(
        text = text,
        style = TextStyle(color = colors.textSecondary, fontSize = ROW_FONT_SP.sp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(SHEET_ROW_PADDING_DP.dp),
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
    val textPrimary: Color,
    val textSecondary: Color,
    val selectedText: Color,
    val accent: Color,
    val danger: Color,
    val undoBackground: Color,
    val undoText: Color,
)

internal fun trackLibraryPalette(dark: Boolean): TrackLibraryPalette =
    if (dark) {
        TrackLibraryPalette(
            background = Color(0xFF121416),
            surface = Color(0xFF1E2124),
            field = Color(0xFF2B2F33),
            selectedSurface = Color(0xFF2E7D32),
            textPrimary = Color.White,
            textSecondary = Color(0xFFB8B8B8),
            selectedText = Color.White,
            accent = Color(0xFF8FD6AE),
            danger = Color(0xFFEF9A9A),
            undoBackground = Color(0xFF323437),
            undoText = Color.White,
        )
    } else {
        TrackLibraryPalette(
            background = Color(0xFFF4F2EE),
            surface = Color.White,
            field = Color(0xFFF2F1EE),
            selectedSurface = Color(0xFF202124),
            textPrimary = Color(0xFF202124),
            textSecondary = Color(0xFF686C72),
            selectedText = Color.White,
            accent = Color(0xFF1E7F53),
            danger = Color(0xFFC62828),
            undoBackground = Color(0xFF323437),
            undoText = Color.White,
        )
    }

private enum class LibrarySheet {
    NONE,
    ACTIONS,
    EDIT,
    MOVE,
    FOLDER,
}

private enum class FolderSheetMode {
    CREATE,
    RENAME,
}

private const val MOVE_TITLE_KEY = "track_library_action_move"
private const val RENAME_FOLDER_TITLE_KEY = "track_library_rename_folder_title"
private const val CREATE_FOLDER_TITLE_KEY = "track_library_create_folder_title"

private const val BACK_GLYPH = "‹"
private const val RENAME_GLYPH = "✎"
private const val MORE_GLYPH = "⋯"

private const val SCREEN_PADDING_DP = 16
private const val SCREEN_TOP_DP = 20
private const val SHEET_MAX_WIDTH_DP = 560
private const val SHEET_CORNER_DP = 16
private const val SHEET_PADDING_DP = 16
private const val SHEET_ROW_PADDING_DP = 10
private const val ROW_GAP_DP = 8
private const val TILE_GAP_DP = 8
private const val TILE_CORNER_DP = 12
private const val TILE_PADDING_DP = 10
private const val CHIP_GAP_DP = 6
private const val CHIP_CORNER_DP = 12
private const val CHIP_PADDING_DP = 9
private const val FIELD_CORNER_DP = 10
private const val FIELD_PADDING_DP = 10
private const val BUTTON_CORNER_DP = 14
private const val BUTTON_PADDING_DP = 12
private const val UNDO_CORNER_DP = 12
private const val DOT_SIZE_DP = 12
private const val DESCRIPTION_HEIGHT_DP = 120
private const val UNDO_RESERVED_DP = 72
private const val ACTION_PADDING_DP = 6
private const val TITLE_FONT_SP = 21
private const val ROW_FONT_SP = 15
private const val WEIGHT_ONE = 1f

private val SCRIM = Color(0x66000000)
