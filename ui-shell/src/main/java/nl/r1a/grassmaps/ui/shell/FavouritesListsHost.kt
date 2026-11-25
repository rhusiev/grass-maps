package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import nl.r1a.grassmaps.plugin.api.model.FavouriteListsState
import nl.r1a.grassmaps.plugin.api.model.FavouriteSort
import nl.r1a.grassmaps.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors

/**
 * The stored coordinates of the selected place, or null when it has none. The
 * browse rows carry the position through from the persisted point (T-0304);
 * the full library is the fallback for a point a query has filtered out.
 */
internal fun selectedPlacePosition(
    state: FavouriteListsState,
    selectedPoint: String?,
): GeoPoint? {
    if (selectedPoint == null) return null
    return state.browse.rows
        .firstOrNull { it.id == selectedPoint }
        ?.position
        ?: state.points.firstOrNull { it.id == selectedPoint }?.position
}

/**
 * The lists sheet (T-0213). The plugin owns the state and the actions; this
 * shell draws rows and sends taps back. It never reads a file or a GPX.
 *
 * The first tap selects a point; a list's "Move here" then moves the selected
 * point. Recolour chips recolour the whole list at once, and Home and Work are
 * set from the point row. Sheet interactions are unexercised on a device in
 * CI; the content resolver and the action wiring are JVM-tested.
 *
 * T-0214 adds the search field and the four-way sort control. Both ask the
 * plugin for a new derived view; this shell draws the rows it is given, so the
 * matching, ordering and description rendering stay in one place.
 *
 * [hiddenGroups] is read from the persisted layer settings on every open, and
 * [onToggleGroupShown] writes an eye tap back through the same store. Neither
 * this host nor the Saved section keeps a second visibility state.
 *
 * [embedded] drops the scrim and card: the combined widgets-and-Saved sheet
 * supplies that chrome and keeps only the list body. It also drops the Lists
 * header title, because the saved section already supplies the one heading.
 *
 * [onShowOnMap] receives the selected place's coordinates. It has no default:
 * the app supplies the camera action, so an unwired sheet cannot compile.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun FavouritesListsHost(
    state: FavouriteListsState,
    strings: Strings,
    hiddenGroups: Set<String>,
    onCreateList: (name: String, color: String) -> Unit,
    onMovePoint: (pointId: String, list: String) -> Unit,
    onRecolourList: (list: String, color: String) -> Unit,
    onSetSpecial: (pointId: String, special: FavouriteSpecial) -> Unit,
    onUndo: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (FavouriteSort) -> Unit,
    onClose: () -> Unit,
    onToggleGroupShown: (String, Boolean) -> Unit = { _, _ -> },
    onShowOnMap: (GeoPoint) -> Unit,
    embedded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (embedded) {
        FavouritesListsBody(
            state = state,
            strings = strings,
            hiddenGroups = hiddenGroups,
            onCreateList = onCreateList,
            onMovePoint = onMovePoint,
            onRecolourList = onRecolourList,
            onSetSpecial = onSetSpecial,
            onUndo = onUndo,
            onQueryChange = onQueryChange,
            onSortChange = onSortChange,
            onClose = onClose,
            onToggleGroupShown = onToggleGroupShown,
            onShowOnMap = onShowOnMap,
            showClose = false,
            showTitle = false,
            scroll = false,
            modifier = modifier,
        )
        return
    }
    Box(
        modifier = modifier.fillMaxSize().background(LocalShellColors.current.scrim).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .padding(SHEET_MARGIN_DP.dp)
                    .widthIn(max = SHEET_MAX_WIDTH_DP.dp)
                    .background(LocalShellColors.current.surface, RoundedCornerShape(SHEET_CORNER_DP.dp))
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .padding(SHEET_PADDING_DP.dp),
        ) {
            FavouritesListsBody(
                state = state,
                strings = strings,
                hiddenGroups = hiddenGroups,
                onCreateList = onCreateList,
                onMovePoint = onMovePoint,
                onRecolourList = onRecolourList,
                onSetSpecial = onSetSpecial,
                onUndo = onUndo,
                onQueryChange = onQueryChange,
                onSortChange = onSortChange,
                onClose = onClose,
                onToggleGroupShown = onToggleGroupShown,
                onShowOnMap = onShowOnMap,
            )
        }
    }
}

/**
 * The saved lists without a scrim or a card. The standalone [FavouritesListsHost]
 * supplies that chrome; the combined widgets-and-Saved sheet supplies its own
 * [ShellBottomSheet] chrome and sets [showClose], [showTitle] and [scroll] to
 * false. The undo and close commands stay in [Header] either way.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun FavouritesListsBody(
    state: FavouriteListsState,
    strings: Strings,
    hiddenGroups: Set<String>,
    onCreateList: (name: String, color: String) -> Unit,
    onMovePoint: (pointId: String, list: String) -> Unit,
    onRecolourList: (list: String, color: String) -> Unit,
    onSetSpecial: (pointId: String, special: FavouriteSpecial) -> Unit,
    onUndo: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (FavouriteSort) -> Unit,
    onClose: () -> Unit,
    onToggleGroupShown: (String, Boolean) -> Unit = { _, _ -> },
    onShowOnMap: (GeoPoint) -> Unit,
    showClose: Boolean = true,
    showTitle: Boolean = true,
    scroll: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val content = favouritesListsContent(state, strings)
    val browse = favouritesBrowseContent(state, strings)
    var selectedPoint by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(PRESET_COLOURS.first()) }
    var query by remember { mutableStateOf(state.browse.query) }
    var segment by remember { mutableStateOf(SavedSegment.PLACES) }
    var savedSearchOpen by remember { mutableStateOf(false) }
    val commands = SAVED_COMMANDS
    val savedContent = savedScreenContent(state, strings, commands, hiddenGroups, segment)
    val selectedPosition = selectedPlacePosition(state, selectedPoint)

    Column(
        modifier = modifier.then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
    ) {
        Header(content = content, onUndo = onUndo, onClose = onClose, showClose = showClose, showTitle = showTitle)
        SavedScreenSection(
            state = state,
            strings = strings,
            hiddenGroups = hiddenGroups,
            segment = segment,
            onToggleGroupShown = onToggleGroupShown,
        )
        SectionTitle(text = strings.resolve(NEW_LIST_KEY))
        ListCreator(
            name = newName,
            strings = strings,
            onNameChange = { newName = it },
            onColorChange = { newColor = it },
            onCreate = {
                val name = newName.trim()
                if (name.isNotEmpty()) {
                    onCreateList(name, newColor)
                    newName = ""
                }
            },
        )
        content.empty?.let { empty -> Line(text = empty, style = secondaryStyle()) }
        content.lists.forEach { list ->
            ListRow(
                row = list,
                canMove = selectedPoint != null,
                moveLabel = strings.resolve(MOVE_KEY),
                onRecolour = { color -> onRecolourList(list.name, color) },
                onMove = { selectedPoint?.let { pointId -> onMovePoint(pointId, list.name) } },
            )
        }
        if (content.points.isNotEmpty() || state.browse.query.isNotBlank()) {
            SectionTitle(text = strings.resolve(POINTS_KEY))
            SearchField(
                label = browse.search,
                hint = browse.searchHint,
                query = query,
                onQueryChange = {
                    query = it
                    onQueryChange(it)
                },
            )
            SortChooser(
                title = browse.sort,
                sorts = browse.sorts,
                onChoose = onSortChange,
            )
            browse.points.forEach { point ->
                PointRow(
                    row = point,
                    selected = point.id == selectedPoint,
                    onSelect = { selectedPoint = if (selectedPoint == point.id) null else point.id },
                    onSetSpecial = { special -> onSetSpecial(point.id, special) },
                    strings = strings,
                )
            }
            browse.noMatches?.let { message -> Line(text = message, style = secondaryStyle()) }
        }
        SavedBottomControls(
            content = savedContent,
            query = query,
            searchOpen = savedSearchOpen,
            newListName = newName,
            placeSelected = selectedPosition != null,
            onChooseSegment = { chosen -> segment = chosen },
            onToggleSearch = { savedSearchOpen = !savedSearchOpen },
            onQueryChange = {
                query = it
                onQueryChange(it)
            },
            onSort = {
                val sorts = FavouriteSort.entries
                val next = sorts[(sorts.indexOf(state.browse.sort) + 1) % sorts.size]
                onSortChange(next)
            },
            onAdd = {
                val name = newName.trim()
                if (name.isNotEmpty()) {
                    onCreateList(name, newColor)
                    newName = ""
                }
            },
            onShowOnMap = { selectedPosition?.let(onShowOnMap) },
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Header(
    content: FavouritesListsContent,
    onUndo: () -> Unit,
    onClose: () -> Unit,
    showClose: Boolean = true,
    showTitle: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showTitle) {
            BasicText(
                text = content.title,
                style = TextStyle(color = LocalShellColors.current.ink, fontSize = TITLE_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
            if (content.undoAvailable) {
                BasicText(
                    text = content.undo,
                    style = TextStyle(color = LocalShellColors.current.muted, fontSize = ROW_FONT_SP.sp),
                    modifier = Modifier.clickable(onClick = onUndo).padding(CLOSE_PADDING_DP.dp),
                )
            }
            if (showClose) {
                BasicText(
                    text = CLOSE_GLYPH,
                    style = TextStyle(color = LocalShellColors.current.ink, fontSize = TITLE_FONT_SP.sp),
                    modifier =
                        Modifier
                            .clickable(onClick = onClose)
                            .semantics { contentDescription = content.close }
                            .padding(CLOSE_PADDING_DP.dp),
                )
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ListCreator(
    name: String,
    strings: Strings,
    onNameChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onCreate: () -> Unit,
) {
    BasicTextField(
        value = name,
        onValueChange = onNameChange,
        singleLine = true,
        textStyle = TextStyle(color = LocalShellColors.current.ink, fontSize = ROW_FONT_SP.sp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(LocalShellColors.current.soft, RoundedCornerShape(FIELD_CORNER_DP.dp))
                .border(
                    1.dp,
                    LocalShellColors.current.controlBorder,
                    RoundedCornerShape(FIELD_CORNER_DP.dp),
                ).padding(FIELD_PADDING_DP.dp),
    )
    ColourChooser(onChoose = onColorChange)
    ActionButton(text = strings.resolve(CREATE_KEY), onClick = onCreate)
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ListRow(
    row: FavouritesListRow,
    canMove: Boolean,
    moveLabel: String,
    onRecolour: (String) -> Unit,
    onMove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatch(color = row.color)
        Column(modifier = Modifier.weight(WEIGHT_ONE)) {
            Line(text = row.name, style = TextStyle(color = LocalShellColors.current.ink, fontSize = ROW_FONT_SP.sp))
            Line(text = row.pointCount.toString(), style = secondaryStyle())
        }
        if (canMove) {
            GlyphButton(glyph = MOVE_GLYPH, description = moveLabel, onClick = onMove)
        }
    }
    ColourChooser(onChoose = onRecolour)
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SearchField(
    label: String,
    hint: String,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Line(text = label, style = secondaryStyle())
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(color = LocalShellColors.current.ink, fontSize = ROW_FONT_SP.sp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(LocalShellColors.current.soft, RoundedCornerShape(FIELD_CORNER_DP.dp))
                .border(
                    1.dp,
                    LocalShellColors.current.controlBorder,
                    RoundedCornerShape(FIELD_CORNER_DP.dp),
                ).padding(FIELD_PADDING_DP.dp),
    )
    if (query.isEmpty()) {
        Line(text = hint, style = secondaryStyle())
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SortChooser(
    title: String,
    sorts: List<FavouritesSortRow>,
    onChoose: (FavouriteSort) -> Unit,
) {
    Line(text = title, style = secondaryStyle())
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
    ) {
        sorts.forEach { row ->
            BasicText(
                text = row.label,
                style =
                    TextStyle(
                        color = if (row.selected) LocalShellColors.current.ink else LocalShellColors.current.muted,
                        fontSize = ROW_FONT_SP.sp,
                        fontWeight = if (row.selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                modifier =
                    Modifier
                        .background(
                            if (row.selected) {
                                LocalShellColors.current.accent.copy(
                                    alpha = SELECTED_ALPHA,
                                )
                            } else {
                                LocalShellColors.current.soft
                            },
                            RoundedCornerShape(FIELD_CORNER_DP.dp),
                        ).border(
                            1.dp,
                            if (row.selected) LocalShellColors.current.accent else LocalShellColors.current.controlBorder,
                            RoundedCornerShape(FIELD_CORNER_DP.dp),
                        ).clickable { onChoose(row.sort) }
                        .padding(FIELD_PADDING_DP.dp),
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PointRow(
    row: FavouritesPointRow,
    selected: Boolean,
    onSelect: () -> Unit,
    onSetSpecial: (FavouriteSpecial) -> Unit,
    strings: Strings,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (selected) LocalShellColors.current.accent.copy(alpha = SELECTED_ALPHA) else Color.Transparent,
                    RoundedCornerShape(FIELD_CORNER_DP.dp),
                ).clickable(onClick = onSelect)
                .padding(FIELD_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(WEIGHT_ONE)) {
            Line(text = row.name, style = TextStyle(color = LocalShellColors.current.ink, fontSize = ROW_FONT_SP.sp))
            Line(text = row.list, style = secondaryStyle())
            row.description?.let { description -> Line(text = description, style = secondaryStyle()) }
        }
        row.special?.let { label -> Line(text = label, style = secondaryStyle()) }
        ActionButton(text = strings.resolve(HOME_KEY), onClick = { onSetSpecial(FavouriteSpecial.HOME) })
        ActionButton(text = strings.resolve(WORK_KEY), onClick = { onSetSpecial(FavouriteSpecial.WORK) })
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ColourChooser(onChoose: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP_DP.dp)) {
        PRESET_COLOURS.forEach { preset ->
            val swatch = favouriteColour(preset)?.let { argb -> Color(argb) } ?: LocalShellColors.current.faint
            Box(
                modifier =
                    Modifier
                        .size(CHIP_SIZE_DP.dp)
                        .background(swatch, RoundedCornerShape(CHIP_CORNER_DP.dp))
                        .clickable { onChoose(preset) },
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Swatch(color: Long?) {
    val swatch = if (color != null) Color(color) else LocalShellColors.current.faint
    Box(
        modifier =
            Modifier
                .size(SWATCH_SIZE_DP.dp)
                .background(swatch, RoundedCornerShape(SWATCH_CORNER_DP.dp)),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun GlyphButton(
    glyph: String,
    description: String,
    onClick: () -> Unit,
) {
    BasicText(
        text = glyph,
        style = TextStyle(color = LocalShellColors.current.ink, fontSize = ACTION_FONT_SP.sp),
        modifier =
            Modifier
                .widthIn(min = MIN_TAP_TARGET_DP.dp)
                .heightIn(min = MIN_TAP_TARGET_DP.dp)
                .clickable(onClick = onClick)
                .semantics { contentDescription = description }
                .padding(CLOSE_PADDING_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
) {
    BasicText(
        text = text,
        style = TextStyle(color = LocalShellColors.current.ink, fontSize = ACTION_FONT_SP.sp),
        modifier =
            Modifier
                .widthIn(min = MIN_TAP_TARGET_DP.dp)
                .heightIn(min = MIN_TAP_TARGET_DP.dp)
                .clickable(onClick = onClick)
                .padding(CLOSE_PADDING_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SectionTitle(text: String) {
    BasicText(
        text = text,
        style = TextStyle(color = LocalShellColors.current.muted, fontSize = SECTION_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Line(
    text: String,
    style: TextStyle,
) {
    BasicText(text = text, style = style)
}

@Composable
private fun secondaryStyle(): TextStyle = TextStyle(color = LocalShellColors.current.muted, fontSize = ROW_FONT_SP.sp)

private const val MOVE_GLYPH = "→"
private const val CLOSE_GLYPH = "×"
private const val NEW_LIST_KEY = "favourites_lists_new_list"
private const val CREATE_KEY = "favourites_lists_create"
private const val POINTS_KEY = "favourites_lists_points"
private const val MOVE_KEY = "favourites_lists_move_here"

private const val SHEET_MARGIN_DP = 16
private const val SHEET_MAX_WIDTH_DP = 560
private const val SHEET_CORNER_DP = 12
private const val SHEET_PADDING_DP = 16
private const val ROW_GAP_DP = 8
private const val MIN_TAP_TARGET_DP = 48
private const val CLOSE_PADDING_DP = 4
private const val FIELD_PADDING_DP = 8
private const val FIELD_CORNER_DP = 6
private const val CHIP_SIZE_DP = 48
private const val CHIP_CORNER_DP = 4
private const val CHIP_GAP_DP = 6
private const val SWATCH_SIZE_DP = 16
private const val SWATCH_CORNER_DP = 4
private const val TITLE_FONT_SP = 20
private const val SECTION_FONT_SP = 13
private const val ROW_FONT_SP = 15
private const val ACTION_FONT_SP = 14
private const val WEIGHT_ONE = 1f

private const val SELECTED_ALPHA = 0.14f

/** Colours the sheet offers; a custom hex picker is backlog B-053. */
internal val PRESET_COLOURS: List<String> =
    listOf("#E53935", "#1E88E5", "#43A047", "#FB8C00", "#8E24AA", "#546E7A")
