package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.api.model.FavouriteSort
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellIcon
import nl.r1a.grassmaps.plugin.ui.api.ShellIcons
import nl.r1a.grassmaps.plugin.ui.api.choiceOutline
import nl.r1a.grassmaps.plugin.ui.api.fieldOutline
import nl.r1a.grassmaps.plugin.ui.api.floatingSurface
import nl.r1a.grassmaps.plugin.ui.api.selectedSegment

/**
 * The commands the Saved screen can actually send. The Map command is exposed
 * so the bar can draw it; the bar enables it only while a saved place is
 * selected, which is the state [SavedBottomControls] receives.
 */
internal val SAVED_COMMANDS: SavedCommands =
    SavedCommands(search = true, map = true, sort = true, add = true, visibility = true)

/** What a tap on a saved row asks the host to do. */
internal class SavedRowActions(
    val selectedPlace: String?,
    val onSelectPlace: (SavedPlaceRow) -> Unit,
    val onRecolour: (list: String, color: String) -> Unit,
    val placeActions: @Composable (SavedPlaceRow) -> Unit,
)

/**
 * The Saved screen's reading area (round 8 `x-saved`, round 9 `k-saved`): the
 * header, the read-only sort and on-map line, and the groups with their eyes,
 * colours and places. Every press that is not attached to one row - search,
 * Show on map, sort and + - is in [SavedBottomControls].
 *
 * The Tracks branch is a notice on purpose when the host cannot open the track
 * library. The library's state stream and its commands are owned by
 * `app/MapScreen.kt`, outside this module; this section receives only derived
 * Saved content and does not invent a second library.
 *
 * [content] is derived once by the host from the state and the persisted
 * hidden groups, so the same rows feed this section and the bottom controls.
 * An eye tap sends [onToggleGroupShown] with the group and its new value; the
 * store writes it and the next composition reads it back. There is no local
 * visibility copy.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun SavedScreenSection(
    content: SavedScreenContent,
    strings: Strings,
    segment: SavedSegment,
    onToggleGroupShown: (String, Boolean) -> Unit,
    rowActions: SavedRowActions,
    onBack: (() -> Unit)?,
    backLabel: String,
    modifier: Modifier = Modifier,
) {
    var expandedGroup by remember { mutableStateOf<String?>(null) }
    var recolourGroup by remember { mutableStateOf<String?>(null) }
    var allPlacesGroup by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        SavedHeading(content = content, onBack = onBack, backLabel = backLabel)
        when (segment) {
            SavedSegment.PLACES -> {
                SavedInfoLine(hint = content.hint, onMap = content.onMapSummary)
                content.empty?.let { empty -> SecondaryLine(text = empty) }
                content.noMatches?.let { message -> SecondaryLine(text = message) }
                content.groups.forEach { group ->
                    val expanded = content.searching || group.name == expandedGroup
                    SavedGroup(
                        row = group,
                        expanded = expanded,
                        onToggleVisibility =
                            groupVisibilityToggle(
                                groupName = group.list ?: group.name,
                                shown = group.shown,
                                onToggleGroupShown = onToggleGroupShown,
                            ),
                        onToggleExpanded = {
                            expandedGroup = if (group.name == expandedGroup) null else group.name
                        },
                        onToggleRecolour = {
                            recolourGroup = if (group.name == recolourGroup) null else group.name
                        },
                    )
                    if (group.canRecolour && group.name == recolourGroup) {
                        ColourRow(
                            label = group.recolourLabel.orEmpty(),
                            selected = group.color,
                            onChoose = { colour ->
                                recolourGroup = null
                                group.list?.let { list -> rowActions.onRecolour(list, colour) }
                            },
                        )
                    }
                    if (expanded) {
                        val showAll = content.searching || group.name == allPlacesGroup
                        val places = if (showAll) group.places + group.remaining else group.places
                        places.forEach { place -> SavedPlace(place, group.color, rowActions) }
                        if (!showAll) {
                            group.moreLabel?.let { more ->
                                MoreLink(text = more, onClick = { allPlacesGroup = group.name })
                            }
                        }
                    }
                }
                if (content.empty == null) {
                    NoteLine(text = strings.resolve(SAVED_STORAGE_KEY))
                }
            }

            SavedSegment.TRACKS -> {
                SecondaryLine(text = content.tracksNotice)
            }
        }
    }
}

/**
 * The bottom of the Saved screen: the Undo pill after a change, the
 * Places|Tracks switch and the bar with search, Show on map, sort and +.
 * [onShowOnMap] reuses the map-command callback contract of `SearchScreenHost`;
 * the host supplies the camera action when it wires the screen.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun SavedBottomControls(
    content: SavedScreenContent,
    query: String,
    undo: String?,
    placeSelected: Boolean,
    onUndo: () -> Unit,
    onChooseSegment: (SavedSegment) -> Unit,
    onQueryChange: (String) -> Unit,
    onSort: () -> Unit,
    onAdd: () -> Unit,
    onShowOnMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
        undo?.let { label ->
            val shell = LocalShellColors.current
            ListUndoBar(
                message = "",
                undo = label,
                background = shell.snackbarBackground,
                ink = shell.snackbarAction,
                action = shell.snackbarAction,
                onUndo = onUndo,
            )
        }
        SegmentedRow(rows = content.segments, onChoose = onChooseSegment)
        SavedBar(
            content = content,
            query = query,
            placeSelected = placeSelected,
            onQueryChange = onQueryChange,
            onSort = onSort,
            onAdd = onAdd,
            onShowOnMap = onShowOnMap,
        )
    }
}

/**
 * The + panel of round 8 `x-saved-add`: the one add action the app has, a new
 * group with a name and a colour.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun NewGroupPanel(
    text: SavedSheetText,
    name: String,
    colour: String,
    onNameChange: (String) -> Unit,
    onColourChange: (String) -> Unit,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalShellColors.current
    val focus = LocalFocusManager.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
        SecondaryLine(text = text.name)
        // Done closes the keyboard: where the window pans instead of resizing (API 29), the keyboard covers Create.
        BasicTextField(
            value = name,
            onValueChange = onNameChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            textStyle = TextStyle(color = colors.ink, fontSize = ListChromeMetrics.LABEL_FONT_SP.sp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                    .background(colors.soft, RoundedCornerShape(FIELD_CORNER_DP.dp))
                    .fieldOutline(colors, RoundedCornerShape(FIELD_CORNER_DP.dp))
                    .padding(horizontal = FIELD_PADDING_DP.dp, vertical = CENTRE_PADDING_DP.dp)
                    .semantics { contentDescription = text.name },
        )
        Swatches(selected = favouriteColour(colour), onChoose = onColourChange)
        ListActionButton(
            text = text.create,
            background = colors.accent,
            ink = colors.onAccent,
            enabled = name.isNotBlank(),
            onClick = onCreate,
            corner = (BUTTON_DP / 2).dp,
            fill = true,
        )
    }
}

/** The sort panel: the plugin's four orders, the current one checked. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun SortPanel(
    sorts: List<FavouritesSortRow>,
    onChoose: (FavouriteSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalShellColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
        sorts.forEach { row ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                        .selectable(selected = row.selected, role = Role.RadioButton) { onChoose(row.sort) }
                        .padding(vertical = SMALL_GAP_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
            ) {
                BasicText(
                    text = row.label,
                    style = bodyStyle().copy(fontWeight = if (row.selected) FontWeight.SemiBold else FontWeight.Normal),
                    modifier = Modifier.weight(ListChromeMetrics.WEIGHT_ONE),
                )
                if (row.selected) {
                    ShellIcon(icon = ShellIcons.Check, tint = colors.accent, modifier = Modifier.size(ListChromeMetrics.ICON_DP.dp))
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SavedHeading(
    content: SavedScreenContent,
    onBack: (() -> Unit)?,
    backLabel: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = SMALL_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(icon = ShellIcons.ChevronLeft, label = backLabel, filled = false, onClick = onBack)
        }
        Column(modifier = Modifier.weight(ListChromeMetrics.WEIGHT_ONE)) {
            BasicText(text = content.title, style = headingStyle())
            SecondaryLine(text = content.subtitle)
        }
    }
}

/** The read-only line under the header: the sort in use and how many groups the map draws. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun SavedInfoLine(
    hint: String,
    onMap: String?,
) {
    val colors = LocalShellColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = SMALL_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SMALL_GAP_DP.dp),
    ) {
        ShellIcon(icon = ShellIcons.Sort, tint = colors.muted, modifier = Modifier.size(INFO_ICON_DP.dp))
        BasicText(text = hint, style = noteStyle(), modifier = Modifier.weight(ListChromeMetrics.WEIGHT_ONE))
        if (onMap != null) {
            ShellIcon(icon = ShellIcons.Eye, tint = colors.muted, modifier = Modifier.size(INFO_ICON_DP.dp))
            BasicText(text = onMap, style = noteStyle(), modifier = Modifier.weight(ListChromeMetrics.WEIGHT_ONE))
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SegmentedRow(
    rows: List<SavedSegmentRow>,
    onChoose: (SavedSegment) -> Unit,
) {
    val colors = LocalShellColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .floatingSurface(colors, RoundedCornerShape(SEGMENT_CORNER_DP.dp))
                .background(colors.soft, RoundedCornerShape(SEGMENT_CORNER_DP.dp))
                .padding(SEGMENT_PADDING_DP.dp),
    ) {
        rows.forEach { row ->
            Box(
                modifier =
                    Modifier
                        .weight(ListChromeMetrics.WEIGHT_ONE)
                        .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                        .then(if (row.selected) Modifier.selectedSegment(RoundedCornerShape(SEGMENT_CORNER_DP.dp)) else Modifier)
                        .background(
                            if (row.selected) selectedSegment(colors) else Color.Transparent,
                            RoundedCornerShape(SEGMENT_CORNER_DP.dp),
                        ).selectable(selected = row.selected, role = Role.Tab) { onChoose(row.segment) }
                        .padding(horizontal = ROW_GAP_DP.dp, vertical = SMALL_GAP_DP.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = row.label,
                    style =
                        TextStyle(
                            color = if (row.selected) colors.ink else colors.muted,
                            fontSize = ListChromeMetrics.LABEL_FONT_SP.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                )
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SavedGroup(
    row: SavedGroupRow,
    expanded: Boolean,
    onToggleVisibility: () -> Unit,
    onToggleExpanded: () -> Unit,
    onToggleRecolour: () -> Unit,
) {
    val colors = LocalShellColors.current
    val textColour = if (row.shown) colors.ink else colors.muted
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = GROUP_ROW_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
    ) {
        GroupBadge(row = row, onClick = onToggleRecolour.takeIf { row.canRecolour })
        // The name opens the places too, the larger target for the same toggle as the chevron.
        Column(
            modifier =
                Modifier
                    .weight(ListChromeMetrics.WEIGHT_ONE)
                    .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                    .clickable(role = Role.Button, onClick = onToggleExpanded),
            verticalArrangement = Arrangement.Center,
        ) {
            BasicText(text = row.name, style = bodyStyle().copy(color = textColour, fontWeight = FontWeight.SemiBold))
            BasicText(text = row.summary, style = noteStyle())
        }
        if (row.canToggleVisibility) {
            IconButton(
                icon = if (row.shown) ShellIcons.Eye else ShellIcons.EyeOff,
                label = row.visibilityLabel ?: row.name,
                filled = row.shown,
                tint = textColour,
                onClick = onToggleVisibility,
            )
        }
        IconButton(
            icon = if (expanded) ShellIcons.ChevronUp else ShellIcons.ChevronDown,
            label = row.name,
            filled = false,
            tint = colors.muted,
            onClick = onToggleExpanded,
        )
    }
}

/**
 * The group's colour as a tinted disc. On a declared list it is the control
 * that opens the colour row, because the colour is the thing it changes.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun GroupBadge(
    row: SavedGroupRow,
    onClick: (() -> Unit)?,
) {
    val colors = LocalShellColors.current
    val colour = row.color?.let { Color(it) } ?: colors.faint
    val label = row.recolourLabel.orEmpty()
    Box(
        modifier =
            Modifier
                .size(MapControlMetrics.TAP_TARGET_DP.dp)
                .background(colour.copy(alpha = BADGE_TINT_ALPHA), CircleShape)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(BADGE_DOT_DP.dp).background(colour, CircleShape))
    }
}

/** The six preset colours as 48 dp targets, the group's current one ringed. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun ColourRow(
    label: String,
    selected: Long?,
    onChoose: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = PLACE_INDENT_DP.dp, bottom = SMALL_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(SMALL_GAP_DP.dp),
    ) {
        BasicText(text = label, style = noteStyle())
        Swatches(selected = selected, onChoose = onChoose)
    }
}

/** Six 48 dp choices in a wrapping row: 56 indent plus 318 dp of swatches clipped below 406 dp screens. */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("ktlint:standard:function-naming")
@Composable
private fun Swatches(
    selected: Long?,
    onChoose: (String) -> Unit,
) {
    val colors = LocalShellColors.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(SWATCH_GAP_DP.dp)) {
        PRESET_COLOURS.forEach { preset ->
            val argb = favouriteColour(preset)
            val isSelected = argb != null && argb == selected
            Box(
                modifier =
                    Modifier
                        .size(MapControlMetrics.TAP_TARGET_DP.dp)
                        .selectable(selected = isSelected, role = Role.RadioButton) { onChoose(preset) }
                        .semantics { contentDescription = preset }
                        .then(if (isSelected) Modifier.choiceOutline(colors, CircleShape, selection = colors.ink) else Modifier)
                        .padding(SWATCH_INSET_DP.dp)
                        .background(argb?.let { Color(it) } ?: colors.faint, CircleShape),
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SavedPlace(
    place: SavedPlaceRow,
    colour: Long?,
    actions: SavedRowActions,
) {
    val colors = LocalShellColors.current
    val selected = place.id == actions.selectedPlace
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                .background(
                    if (selected) colors.accent.copy(alpha = SELECTED_ALPHA) else Color.Transparent,
                    RoundedCornerShape(FIELD_CORNER_DP.dp),
                ).selectable(selected = selected, role = Role.Button) { actions.onSelectPlace(place) }
                .padding(start = PLACE_INDENT_DP.dp, end = SMALL_GAP_DP.dp, top = SMALL_GAP_DP.dp, bottom = SMALL_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(PLACE_DOT_DP.dp)
                    .background(colour?.let { Color(it) } ?: colors.muted, CircleShape),
        )
        Column(modifier = Modifier.weight(ListChromeMetrics.WEIGHT_ONE)) {
            BasicText(text = place.name, style = bodyStyle())
            place.detail?.let { detail -> BasicText(text = detail, style = noteStyle()) }
        }
        place.special?.let { special -> BasicText(text = special, style = bodyStyle().copy(fontWeight = FontWeight.SemiBold)) }
    }
    if (selected) {
        actions.placeActions(place)
    }
}

/**
 * The selected place's own actions, under its row: Show on map, Home, Work and
 * a move to each other declared list.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun PlaceActions(
    text: SavedSheetText,
    place: SavedPlaceRow,
    moveTargets: List<SavedMoveTarget>,
    onShowOnMap: () -> Unit,
    onSetHome: () -> Unit,
    onSetWork: () -> Unit,
    onMove: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = PLACE_INDENT_DP.dp + PLACE_DOT_DP.dp + ROW_GAP_DP.dp)) {
        if (place.position != null) {
            ActionRow(text = text.showOnMap, onClick = onShowOnMap)
        }
        ActionRow(text = text.setHome, onClick = onSetHome)
        ActionRow(text = text.setWork, onClick = onSetWork)
        moveTargets.forEach { target -> ActionRow(text = target.label, onClick = { onMove(target.list) }) }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ActionRow(
    text: String,
    onClick: () -> Unit,
) {
    BasicText(
        text = text,
        style = bodyStyle().copy(color = LocalShellColors.current.accent, fontWeight = FontWeight.SemiBold),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = CENTRE_PADDING_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun MoreLink(
    text: String,
    onClick: () -> Unit,
) {
    BasicText(
        text = text,
        style = noteStyle().copy(color = LocalShellColors.current.accent, fontWeight = FontWeight.SemiBold),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = PLACE_INDENT_DP.dp, top = CENTRE_PADDING_DP.dp, bottom = CENTRE_PADDING_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SavedBar(
    content: SavedScreenContent,
    query: String,
    placeSelected: Boolean,
    onQueryChange: (String) -> Unit,
    onSort: () -> Unit,
    onAdd: () -> Unit,
    onShowOnMap: () -> Unit,
) {
    val search = content.actions.firstOrNull { it.action == SavedBarAction.SEARCH } ?: return
    val buttons =
        content.actions.mapNotNull { action ->
            when (action.action) {
                SavedBarAction.SEARCH -> null
                SavedBarAction.MAP -> ListBarButton(ShellIcons.MapFolded, action.label, enabled = placeSelected, onClick = onShowOnMap)
                SavedBarAction.SORT -> ListBarButton(ShellIcons.Sort, action.label, onClick = onSort)
                SavedBarAction.ADD -> ListBarButton(ShellIcons.Plus, action.label, onClick = onAdd)
            }
        }
    ListBottomBar(
        colors = LocalShellColors.current,
        placeholder = search.label,
        query = query,
        onQueryChange = onQueryChange,
        buttons = buttons,
    )
}

/** A 48 dp round icon control in the Saved rows and header. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun IconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    filled: Boolean = true,
    tint: Color? = null,
) {
    RoundIconButton(
        colors = LocalShellColors.current,
        icon = icon,
        label = label,
        onClick = onClick,
        filled = filled,
        tint = tint,
    )
}

/** The selected segment: white on the light theme, the line grey on the dark one. */
private fun selectedSegment(colors: ShellColors): Color = if (colors.dark) colors.line else colors.surface

@Composable
private fun headingStyle(): TextStyle =
    TextStyle(color = LocalShellColors.current.ink, fontSize = TITLE_FONT_SP.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun bodyStyle(): TextStyle = TextStyle(color = LocalShellColors.current.ink, fontSize = ListChromeMetrics.LABEL_FONT_SP.sp)

@Composable
private fun noteStyle(): TextStyle = TextStyle(color = LocalShellColors.current.muted, fontSize = NOTE_FONT_SP.sp)

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SecondaryLine(text: String) {
    BasicText(
        text = text,
        style = TextStyle(color = LocalShellColors.current.muted, fontSize = ListChromeMetrics.LABEL_FONT_SP.sp),
        modifier = Modifier.padding(vertical = SMALL_GAP_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun NoteLine(text: String) {
    BasicText(text = text, style = noteStyle(), modifier = Modifier.padding(vertical = CENTRE_PADDING_DP.dp))
}

internal const val SAVED_STORAGE_KEY = "saved_screen_storage"

private const val GROUP_ROW_DP = 56
private const val BUTTON_DP = 50
private const val INFO_ICON_DP = 16
private const val BADGE_DOT_DP = 16
private const val PLACE_DOT_DP = 12
private const val ROW_GAP_DP = 8
private const val SMALL_GAP_DP = 4
private const val SWATCH_GAP_DP = 6
private const val SWATCH_INSET_DP = 5
private const val PLACE_INDENT_DP = 56
private const val CENTRE_PADDING_DP = 12
private const val FIELD_PADDING_DP = 12
private const val FIELD_CORNER_DP = 10
private const val SEGMENT_PADDING_DP = 4
private const val SEGMENT_CORNER_DP = 24
private const val TITLE_FONT_SP = 24
private const val NOTE_FONT_SP = 13
private const val BADGE_TINT_ALPHA = 0.16f
private const val SELECTED_ALPHA = 0.14f
