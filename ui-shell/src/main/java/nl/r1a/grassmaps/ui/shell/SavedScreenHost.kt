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
 * The Saved screen section (T-0612, round 9 `k-saved`). It draws the state the
 * [savedScreenContent] resolver derives: group eyes, expanded place rows, the
 * Places|Tracks segments and a bottom bar. Every control is a 48 dp target and
 * the colours come from the round 9/10 shell tokens.
 *
 * The section is additive inside the existing lists sheet. The sheet's list
 * creator and point rows stay below it, so a library can still be edited. The
 * map action is absent: the lists sheet has no "show saved on map" command, so
 * the resolver omits it rather than drawing a dead button.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun SavedScreenSection(
    state: FavouriteListsState,
    strings: Strings,
    query: String,
    newListName: String,
    newListColor: String,
    onQueryChange: (String) -> Unit,
    onSortChange: (FavouriteSort) -> Unit,
    onCreateList: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hiddenGroups by remember { mutableStateOf(emptySet<String>()) }
    var expandedGroup by remember { mutableStateOf<String?>(null) }
    var segment by remember { mutableStateOf(SavedSegment.PLACES) }
    var searchOpen by remember { mutableStateOf(false) }

    val commands = SavedCommands(search = true, map = false, sort = true, add = true, visibility = true)
    val content = savedScreenContent(state, strings, commands, hiddenGroups, segment)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
        SavedHeading(content = content)
        SegmentedRow(
            rows = content.segments,
            onChoose = { chosen -> segment = chosen },
        )
        when (segment) {
            SavedSegment.PLACES -> {
                if (content.empty != null) {
                    SecondaryLine(text = content.empty)
                } else {
                    content.groups.forEach { group ->
                        SavedGroup(
                            row = group,
                            expanded = group.name == expandedGroup,
                            onToggleVisibility = {
                                hiddenGroups =
                                    if (group.shown) hiddenGroups + group.name else hiddenGroups - group.name
                            },
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
        SavedBar(
            content = content,
            query = query,
            searchOpen = searchOpen,
            newListName = newListName,
            onToggleSearch = { searchOpen = !searchOpen },
            onQueryChange = onQueryChange,
            onSort = {
                val sorts = FavouriteSort.entries
                val next = sorts[(sorts.indexOf(state.browse.sort) + 1) % sorts.size]
                onSortChange(next)
            },
            onAdd = {
                val name = newListName.trim()
                if (name.isNotEmpty()) onCreateList(name, newListColor)
            },
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
    onToggleSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSort: () -> Unit,
    onAdd: () -> Unit,
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
