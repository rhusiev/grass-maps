package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.api.model.FavouriteListsState
import nl.r1a.grassmaps.plugin.api.model.FavouriteSort
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors

/**
 * The commands the Saved sheet can actually send. The Map command is exposed
 * so the bar can draw it; the bar enables it only while a saved place is
 * selected, which is the state [SavedBottomControls] receives.
 */
internal val SAVED_COMMANDS: SavedCommands =
    SavedCommands(search = true, map = true, sort = true, add = true, visibility = true)

/**
 * The Saved screen's reading area (T-0612, round 9 `k-saved`). It draws the
 * heading, the group eyes and the expanded place rows the [savedScreenContent]
 * resolver derives. The Places|Tracks switch and the action bar are drawn by
 * [SavedBottomControls] after the whole list, so the controls stay in thumb
 * reach at the bottom of the sheet. Every control is a 48 dp target and the
 * colours come from the round 9/10 shell tokens.
 *
 * The Tracks branch is a notice on purpose. The track library's state stream
 * and its thirteen commands are owned by `app/MapScreen.kt`, outside this
 * module; this section receives only [FavouriteListsState]. It cannot show the
 * real library without inventing a state, so it keeps the notice honest.
 *
 * [hiddenGroups] comes from the persisted layer settings on every composition,
 * so closing and reopening the sheet cannot reset the eyes. An eye tap sends
 * [onToggleGroupShown] with the group and its new value; the store writes it
 * and the next composition reads it back. There is no local visibility copy.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun SavedScreenSection(
    state: FavouriteListsState,
    strings: Strings,
    hiddenGroups: Set<String>,
    segment: SavedSegment,
    onToggleGroupShown: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedGroup by remember { mutableStateOf<String?>(null) }

    val commands = SAVED_COMMANDS
    val content = savedScreenContent(state, strings, commands, hiddenGroups, segment)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
        SavedHeading(content = content)
        when (segment) {
            SavedSegment.PLACES -> {
                if (content.empty != null) {
                    SecondaryLine(text = content.empty)
                } else {
                    content.groups.forEach { group ->
                        SavedGroup(
                            row = group,
                            expanded = group.name == expandedGroup,
                            onToggleVisibility =
                                groupVisibilityToggle(
                                    groupName = group.name,
                                    shown = group.shown,
                                    onToggleGroupShown = onToggleGroupShown,
                                ),
                            onToggleExpanded = {
                                expandedGroup = if (group.name == expandedGroup) null else group.name
                            },
                        )
                    }
                }
            }

            SavedSegment.TRACKS -> {
                SecondaryLine(text = content.tracksNotice)
            }
        }
    }
}

/**
 * The bottom of the Saved sheet: the Places|Tracks switch above the action
 * bar, both after every list row. The chosen segment is owned by the caller so
 * it survives the move out of the reading area. [onShowOnMap] reuses the
 * existing map-command callback contract of `SearchScreenHost`; the host
 * supplies the camera action when it wires the sheet.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun SavedBottomControls(
    content: SavedScreenContent,
    query: String,
    searchOpen: Boolean,
    newListName: String,
    placeSelected: Boolean,
    onChooseSegment: (SavedSegment) -> Unit,
    onToggleSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSort: () -> Unit,
    onAdd: () -> Unit,
    onShowOnMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
        SegmentedRow(rows = content.segments, onChoose = onChooseSegment)
        SavedBar(
            content = content,
            query = query,
            searchOpen = searchOpen,
            newListName = newListName,
            placeSelected = placeSelected,
            onToggleSearch = onToggleSearch,
            onQueryChange = onQueryChange,
            onSort = onSort,
            onAdd = onAdd,
            onShowOnMap = onShowOnMap,
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SavedHeading(content: SavedScreenContent) {
    BasicText(text = content.title, style = headingStyle())
    SecondaryLine(text = content.subtitle)
    SecondaryLine(text = content.hint)
    SecondaryLine(text = content.onMapSummary)
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SegmentedRow(
    rows: List<SavedSegmentRow>,
    onChoose: (SavedSegment) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(LocalShellColors.current.soft, RoundedCornerShape(SEGMENT_CORNER_DP.dp))
                .padding(SEGMENT_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
    ) {
        rows.forEach { row ->
            val colors = LocalShellColors.current
            BasicText(
                text = row.label,
                style =
                    TextStyle(
                        color = if (row.selected) colors.onAccent else colors.muted,
                        fontSize = LABEL_FONT_SP.sp,
                        fontWeight = if (row.selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                modifier =
                    Modifier
                        .heightIn(min = MIN_TAP_TARGET_DP.dp)
                        .weight(WEIGHT_ONE)
                        .background(
                            if (row.selected) colors.accent else Color.Transparent,
                            RoundedCornerShape(SEGMENT_CORNER_DP.dp),
                        ).clickable { onChoose(row.segment) }
                        .padding(horizontal = ROW_GAP_DP.dp, vertical = CENTRE_PADDING_DP.dp)
                        .semantics { contentDescription = row.label },
            )
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
) {
    val colors = LocalShellColors.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(SWATCH_DP.dp)
                        .background(
                            row.color?.let { Color(it) } ?: colors.faint,
                            RoundedCornerShape(SWATCH_DP.dp),
                        ),
            )
            Column(modifier = Modifier.weight(WEIGHT_ONE)) {
                BasicText(text = row.name, style = bodyStyle())
                SecondaryLine(text = row.summary)
            }
            if (row.canToggleVisibility) {
                TapIcon(
                    glyph = if (row.shown) EYE_ON else EYE_OFF,
                    label = row.visibilityLabel,
                    onClick = onToggleVisibility,
                )
            }
            TapIcon(
                glyph = if (expanded) CHEVRON_UP else CHEVRON_DOWN,
                label = row.name,
                onClick = onToggleExpanded,
            )
        }
        if (expanded) {
            row.places.forEach { place ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = MIN_TAP_TARGET_DP.dp)
                            .padding(start = PLACE_INDENT_DP.dp)
                            .padding(vertical = CENTRE_PADDING_DP.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(WEIGHT_ONE)) {
                        BasicText(text = place.name, style = bodyStyle())
                        SecondaryLine(text = place.detail)
                    }
                    place.special?.let { special -> SecondaryLine(text = special) }
                }
            }
            row.moreLabel?.let { more -> SecondaryLine(text = more) }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SavedBar(
    content: SavedScreenContent,
    query: String,
    searchOpen: Boolean,
    newListName: String,
    placeSelected: Boolean,
    onToggleSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSort: () -> Unit,
    onAdd: () -> Unit,
    onShowOnMap: () -> Unit,
) {
    val colors = LocalShellColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(BAR_CORNER_DP.dp))
                .padding(BAR_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
    ) {
        if (searchOpen) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = colors.ink, fontSize = LABEL_FONT_SP.sp),
                modifier =
                    Modifier
                        .heightIn(min = MIN_TAP_TARGET_DP.dp)
                        .weight(WEIGHT_ONE)
                        .background(colors.soft, RoundedCornerShape(BAR_CORNER_DP.dp))
                        .padding(horizontal = ROW_GAP_DP.dp, vertical = CENTRE_PADDING_DP.dp),
            )
        } else {
            content.actions.firstOrNull { it.action == SavedBarAction.SEARCH }?.let { action ->
                TapIcon(glyph = SEARCH_GLYPH, label = action.label, onClick = onToggleSearch)
            }
        }
        content.actions.firstOrNull { it.action == SavedBarAction.MAP }?.let { action ->
            TapIcon(
                glyph = MAP_GLYPH,
                label = action.label,
                enabled = placeSelected,
                onClick = onShowOnMap,
            )
        }
        content.actions.firstOrNull { it.action == SavedBarAction.SORT }?.let { action ->
            TapIcon(glyph = SORT_GLYPH, label = action.label, onClick = onSort)
        }
        content.actions.firstOrNull { it.action == SavedBarAction.ADD }?.let { action ->
            TapIcon(
                glyph = ADD_GLYPH,
                label = action.label,
                enabled = newListName.isNotBlank(),
                onClick = onAdd,
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun TapIcon(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    BasicText(
        text = glyph,
        style =
            TextStyle(
                color = if (enabled) LocalShellColors.current.ink else LocalShellColors.current.muted,
                fontSize = ICON_FONT_SP.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        modifier =
            Modifier
                .widthIn(min = MIN_TAP_TARGET_DP.dp)
                .heightIn(min = MIN_TAP_TARGET_DP.dp)
                .background(LocalShellColors.current.soft, RoundedCornerShape(ICON_CORNER_DP.dp))
                .padding(horizontal = ROW_GAP_DP.dp, vertical = CENTRE_PADDING_DP.dp)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = label },
    )
}

@Composable
private fun headingStyle(): TextStyle =
    TextStyle(color = LocalShellColors.current.ink, fontSize = TITLE_FONT_SP.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun bodyStyle(): TextStyle = TextStyle(color = LocalShellColors.current.ink, fontSize = LABEL_FONT_SP.sp)

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SecondaryLine(text: String) {
    BasicText(text = text, style = TextStyle(color = LocalShellColors.current.muted, fontSize = LABEL_FONT_SP.sp))
}

private const val EYE_ON = "◉"
private const val EYE_OFF = "○"
private const val CHEVRON_DOWN = "⌄"
private const val CHEVRON_UP = "⌃"
private const val SEARCH_GLYPH = "⌕"
private const val MAP_GLYPH = "▤"
private const val SORT_GLYPH = "↕"
private const val ADD_GLYPH = "+"

private const val MIN_TAP_TARGET_DP = 48
private const val SWATCH_DP = 38
private const val ROW_GAP_DP = 8
private const val PLACE_INDENT_DP = 24
private const val CENTRE_PADDING_DP = 14
private const val SEGMENT_PADDING_DP = 4
private const val BAR_PADDING_DP = 7
private const val BAR_CORNER_DP = 29
private const val SEGMENT_CORNER_DP = 24
private const val ICON_CORNER_DP = 20
private const val TITLE_FONT_SP = 21
private const val LABEL_FONT_SP = 15
private const val ICON_FONT_SP = 20
private const val WEIGHT_ONE = 1f
