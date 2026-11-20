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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.ui.api.LocalShellColors
import nl.r1a.grassmap.plugin.ui.api.inkOn

/** The two tabs of the map layout sheet (T-0606). */
enum class MapLayoutTab {
    Buttons,
    Widgets,
}

/** One item the sheet lists. [shown] means it is already on the map. */
data class LayoutSheetItem(
    val key: String,
    val title: String,
    val shown: Boolean,
)

/**
 * The items of both tabs: the buttons in catalog order, and the widgets split
 * into the ones that are not shown and the ones on screen.
 */
data class MapLayoutSheetContent(
    val buttons: List<LayoutSheetItem>,
    val hiddenWidgets: List<LayoutSheetItem>,
    val shownWidgets: List<LayoutSheetItem>,
)

/**
 * Builds both tabs from the app's catalogs. The catalogs are the whole list:
 * an item the app does not have cannot appear, and the catalog order is kept.
 * Widgets are split into the ones that are not shown and the ones on screen.
 */
fun mapLayoutSheetContent(
    buttonKeys: List<String>,
    buttonTitle: (String) -> String,
    placedButtons: Set<String>,
    widgetKeys: List<String>,
    widgetTitle: (String) -> String,
    placedWidgets: Set<String>,
): MapLayoutSheetContent =
    MapLayoutSheetContent(
        buttons = buttonKeys.map { key -> LayoutSheetItem(key, buttonTitle(key), key in placedButtons) },
        hiddenWidgets =
            widgetKeys
                .filter { key -> key !in placedWidgets }
                .map { key -> LayoutSheetItem(key, widgetTitle(key), shown = false) },
        shownWidgets =
            widgetKeys
                .filter { key -> key in placedWidgets }
                .map { key -> LayoutSheetItem(key, widgetTitle(key), shown = true) },
    )

/** The resolved text of the sheet. */
data class MapLayoutSheetLabels(
    val buttonsTab: String,
    val widgetsTab: String,
    val close: String,
    val hintButtons: String,
    val hintWidgets: String,
    val notShown: String,
    val onScreen: String,
    val onScreenNote: String,
    val quickAccess: String,
    val none: String,
    val quickAccessDescription: String,
    val arc: String,
    val arcDescription: String,
    val placeDescription: (String) -> String,
    val removeDescription: (String) -> String,
)

/**
 * The variant D layout sheet (T-0606): a panel over the map with a Buttons |
 * Widgets switch. It never moves the map or its controls. A tap on an item
 * adds it to the first free slot or removes it from its slot; the quick-access
 * row chooses the command of the separate quick-access button, and the arc row
 * turns the one-thumb arrangement on or off.
 *
 * Every colour is a round 9 shell token, so the sheet follows the theme.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("ktlint:standard:function-naming")
@Composable
fun MapLayoutSheet(
    content: MapLayoutSheetContent,
    tab: MapLayoutTab,
    labels: MapLayoutSheetLabels,
    quickAccessTitle: String?,
    arc: Boolean,
    onTab: (MapLayoutTab) -> Unit,
    onItem: (LayoutSheetItem) -> Unit,
    onQuickAccess: (String?) -> Unit,
    onArc: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalShellColors.current
    var quickAccessOpen by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(topStart = SHEET_CORNER_DP.dp, topEnd = SHEET_CORNER_DP.dp))
                .border(1.dp, colors.ring, RoundedCornerShape(topStart = SHEET_CORNER_DP.dp, topEnd = SHEET_CORNER_DP.dp))
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SHEET_PADDING_DP.dp)
                .padding(bottom = SHEET_PADDING_DP.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .padding(top = HANDLE_TOP_DP.dp, bottom = HANDLE_BOTTOM_DP.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(HANDLE_WIDTH_DP.dp, HANDLE_HEIGHT_DP.dp)
                    .background(colors.handle, RoundedCornerShape(HANDLE_RADIUS_DP.dp)),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabSwitch(tab, labels, onTab, Modifier.weight(1f))
            CloseButton(labels.close, onClose)
        }
        Hint(if (tab == MapLayoutTab.Buttons) labels.hintButtons else labels.hintWidgets)
        when (tab) {
            MapLayoutTab.Buttons -> {
                ButtonGrid(content.buttons, labels, onItem)
                QuickAccessRow(
                    label = labels.quickAccess,
                    title = quickAccessTitle ?: labels.none,
                    description = labels.quickAccessDescription,
                    open = quickAccessOpen,
                    onToggle = { quickAccessOpen = !quickAccessOpen },
                )
                if (quickAccessOpen) {
                    QuickAccessChoices(content.buttons, labels, onQuickAccess)
                }
                ArcRow(labels.arc, labels.arcDescription, arc, onArc)
            }

            MapLayoutTab.Widgets -> {
                if (content.hiddenWidgets.isNotEmpty()) {
                    SectionTitle(labels.notShown)
                    WidgetChips(content.hiddenWidgets, labels, onItem)
                }
                if (content.shownWidgets.isNotEmpty()) {
                    SectionTitle(labels.onScreen)
                    WidgetChips(content.shownWidgets, labels, onItem)
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun TabSwitch(
    tab: MapLayoutTab,
    labels: MapLayoutSheetLabels,
    onTab: (MapLayoutTab) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalShellColors.current
    Row(
        modifier =
            modifier
                .background(colors.soft, RoundedCornerShape(TAB_CORNER_DP.dp))
                .padding(TAB_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(TAB_GAP_DP.dp),
    ) {
        TabButton(labels.buttonsTab, tab == MapLayoutTab.Buttons, Modifier.weight(1f)) { onTab(MapLayoutTab.Buttons) }
        TabButton(labels.widgetsTab, tab == MapLayoutTab.Widgets, Modifier.weight(1f)) { onTab(MapLayoutTab.Widgets) }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalShellColors.current
    val shape = RoundedCornerShape(TAB_ITEM_CORNER_DP.dp)
    Box(
        modifier =
            modifier
                .height(TAB_HEIGHT_DP.dp)
                .clip(shape)
                .background(if (selected) colors.selectedChip else colors.soft, shape)
                .selectable(selected = selected, role = Role.Tab, onClick = onClick)
                .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style =
                TextStyle(
                    color = if (selected) inkOn(colors.selectedChip, colors) else colors.muted,
                    fontSize = TAB_FONT_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun CloseButton(
    description: String,
    onClose: () -> Unit,
) {
    val colors = LocalShellColors.current
    Box(
        modifier =
            Modifier
                .size(CLOSE_SIZE_DP.dp)
                .background(colors.soft, CircleShape)
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = CLOSE_GLYPH,
            style = TextStyle(color = colors.ink, fontSize = CLOSE_GLYPH_SP.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Hint(text: String) {
    BasicText(
        text = text,
        style = TextStyle(color = LocalShellColors.current.muted, fontSize = HINT_FONT_SP.sp, lineHeight = HINT_LINE_SP.sp),
        modifier = Modifier.padding(top = HINT_TOP_DP.dp, bottom = HINT_BOTTOM_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ButtonGrid(
    items: List<LayoutSheetItem>,
    labels: MapLayoutSheetLabels,
    onItem: (LayoutSheetItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp)) {
        items.chunked(TILES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp)) {
                row.forEach { item ->
                    ButtonTile(item, labels, onItem, Modifier.weight(1f))
                }
                repeat(TILES_PER_ROW - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ButtonTile(
    item: LayoutSheetItem,
    labels: MapLayoutSheetLabels,
    onItem: (LayoutSheetItem) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalShellColors.current
    val shape = RoundedCornerShape(TILE_CORNER_DP.dp)
    Box(
        modifier =
            modifier
                .height(TILE_HEIGHT_DP.dp)
                .clip(shape)
                .background(colors.soft, shape)
                .alpha(if (item.shown) SHOWN_ALPHA else 1f)
                .clickable(role = Role.Button) { onItem(item) }
                .semantics {
                    contentDescription =
                        if (item.shown) labels.removeDescription(item.title) else labels.placeDescription(item.title)
                }.padding(horizontal = TILE_PADDING_DP.dp, vertical = TILE_VERTICAL_PADDING_DP.dp),
    ) {
        BasicText(
            text = if (item.shown) REMOVE_GLYPH else ADD_GLYPH,
            style = TextStyle(color = colors.muted, fontSize = TILE_GLYPH_SP.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.align(Alignment.TopEnd).clearAndSetSemantics {},
        )
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            BasicText(
                text = item.title,
                style =
                    TextStyle(
                        color = colors.ink,
                        fontSize = TILE_FONT_SP.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                maxLines = 2,
            )
            if (item.shown) {
                BasicText(
                    text = labels.onScreenNote,
                    style = TextStyle(color = colors.muted, fontSize = TILE_NOTE_SP.sp),
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("ktlint:standard:function-naming")
@Composable
private fun WidgetChips(
    items: List<LayoutSheetItem>,
    labels: MapLayoutSheetLabels,
    onItem: (LayoutSheetItem) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp),
    ) {
        items.forEach { item -> WidgetChip(item, labels, onItem) }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun WidgetChip(
    item: LayoutSheetItem,
    labels: MapLayoutSheetLabels,
    onItem: (LayoutSheetItem) -> Unit,
) {
    val colors = LocalShellColors.current
    val shape = RoundedCornerShape(CHIP_HEIGHT_DP.dp)
    Row(
        modifier =
            Modifier
                .height(CHIP_HEIGHT_DP.dp)
                .clip(shape)
                .background(colors.soft, shape)
                .alpha(if (item.shown) SHOWN_ALPHA else 1f)
                .clickable(role = Role.Button) { onItem(item) }
                .semantics {
                    contentDescription =
                        if (item.shown) labels.removeDescription(item.title) else labels.placeDescription(item.title)
                }.padding(horizontal = CHIP_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CHIP_GLYPH_GAP_DP.dp),
    ) {
        BasicText(
            text = if (item.shown) REMOVE_GLYPH else ADD_GLYPH,
            style = TextStyle(color = colors.muted, fontSize = CHIP_GLYPH_SP.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.clearAndSetSemantics {},
        )
        BasicText(text = item.title, style = TextStyle(color = colors.ink, fontSize = CHIP_FONT_SP.sp))
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SectionTitle(text: String) {
    BasicText(
        text = text.uppercase(),
        style =
            TextStyle(
                color = LocalShellColors.current.muted,
                fontSize = SECTION_FONT_SP.sp,
                fontWeight = FontWeight.Bold,
            ),
        modifier =
            Modifier
                .padding(top = SECTION_TOP_DP.dp, bottom = SECTION_BOTTOM_DP.dp)
                .semantics { heading() },
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun QuickAccessRow(
    label: String,
    title: String,
    description: String,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalShellColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { contentDescription = description }
                .padding(vertical = ROW_VERTICAL_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
    ) {
        BasicText(
            text = QUICK_ACCESS_GLYPH,
            style = TextStyle(color = colors.muted, fontSize = ROW_GLYPH_SP.sp),
            modifier = Modifier.clearAndSetSemantics {},
        )
        Row(modifier = Modifier.weight(1f)) {
            BasicText(
                text = "$label · ",
                style = TextStyle(color = colors.ink, fontSize = ROW_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
            )
            BasicText(
                text = title,
                style = TextStyle(color = colors.amber, fontSize = ROW_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
            )
        }
        BasicText(
            text = if (open) CHEVRON_UP_GLYPH else CHEVRON_DOWN_GLYPH,
            style = TextStyle(color = colors.faint, fontSize = ROW_GLYPH_SP.sp),
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("ktlint:standard:function-naming")
@Composable
private fun QuickAccessChoices(
    buttons: List<LayoutSheetItem>,
    labels: MapLayoutSheetLabels,
    onQuickAccess: (String?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp),
    ) {
        QuickAccessChoice(labels.none, labels.quickAccessDescription) { onQuickAccess(null) }
        buttons.forEach { item ->
            QuickAccessChoice(item.title, item.title) { onQuickAccess(item.key) }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun QuickAccessChoice(
    text: String,
    description: String,
    onClick: () -> Unit,
) {
    val colors = LocalShellColors.current
    val shape = RoundedCornerShape(CHIP_HEIGHT_DP.dp)
    Box(
        modifier =
            Modifier
                .height(CHIP_HEIGHT_DP.dp)
                .background(colors.soft, shape)
                .clip(shape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description }
                .padding(horizontal = CHIP_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = TextStyle(color = colors.ink, fontSize = CHIP_FONT_SP.sp),
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ArcRow(
    title: String,
    description: String,
    arc: Boolean,
    onArc: (Boolean) -> Unit,
) {
    val colors = LocalShellColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = arc, role = Role.Switch, onValueChange = onArc)
                .semantics { contentDescription = description }
                .padding(vertical = ROW_VERTICAL_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
    ) {
        BasicText(
            text = ARC_GLYPH,
            style = TextStyle(color = colors.muted, fontSize = ROW_GLYPH_SP.sp),
            modifier = Modifier.clearAndSetSemantics {},
        )
        BasicText(
            text = title,
            style = TextStyle(color = colors.ink, fontSize = ROW_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
        ShellSwitch(arc)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ShellSwitch(on: Boolean) {
    val colors = LocalShellColors.current
    Box(
        modifier =
            Modifier
                .size(SWITCH_WIDTH_DP.dp, SWITCH_HEIGHT_DP.dp)
                .background(if (on) colors.accent else colors.soft, RoundedCornerShape(SWITCH_RADIUS_DP.dp))
                .border(
                    SWITCH_BORDER_DP.dp,
                    if (on) colors.accent else colors.controlBorder,
                    RoundedCornerShape(SWITCH_RADIUS_DP.dp),
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = SWITCH_BORDER_DP.dp)
                    .size(SWITCH_KNOB_DP.dp)
                    .background(if (on) colors.onAccent else colors.offSwitchKnob, CircleShape),
        )
    }
}

private const val SHEET_CORNER_DP = 22
private const val SHEET_PADDING_DP = 16
private const val HANDLE_TOP_DP = 9
private const val HANDLE_BOTTOM_DP = 10
private const val HANDLE_WIDTH_DP = 36
private const val HANDLE_HEIGHT_DP = 4
private const val HANDLE_RADIUS_DP = 2
private const val TAB_CORNER_DP = 24
private const val TAB_ITEM_CORNER_DP = 18
private const val TAB_PADDING_DP = 3
private const val TAB_GAP_DP = 3
private const val TAB_HEIGHT_DP = 32
private const val TAB_FONT_SP = 13.5f
private const val CLOSE_SIZE_DP = 40
private const val CLOSE_GLYPH = "⌄"
private const val CLOSE_GLYPH_SP = 21f
private const val HINT_FONT_SP = 12f
private const val HINT_LINE_SP = 17f
private const val HINT_TOP_DP = 9
private const val HINT_BOTTOM_DP = 8
private const val TILES_PER_ROW = 4
private const val TILE_GAP_DP = 7
private const val TILE_HEIGHT_DP = 58
private const val TILE_CORNER_DP = 14
private const val TILE_PADDING_DP = 6
private const val TILE_VERTICAL_PADDING_DP = 5
private const val TILE_FONT_SP = 11.5f
private const val TILE_NOTE_SP = 9.5f
private const val TILE_GLYPH_SP = 12f
private const val ADD_GLYPH = "+"
private const val REMOVE_GLYPH = "−"
private const val SHOWN_ALPHA = 0.45f
private const val CHIP_HEIGHT_DP = 30
private const val CHIP_PADDING_DP = 10
private const val CHIP_FONT_SP = 12f
private const val CHIP_GLYPH_SP = 11f
private const val CHIP_GLYPH_GAP_DP = 5
private const val SECTION_FONT_SP = 10.5f
private const val SECTION_TOP_DP = 12
private const val SECTION_BOTTOM_DP = 6
private const val ROW_VERTICAL_DP = 7
private const val ROW_GAP_DP = 12
private const val ROW_FONT_SP = 14f
private const val ROW_GLYPH_SP = 20f
private const val QUICK_ACCESS_GLYPH = "◎"
private const val ARC_GLYPH = "☝"
private const val CHEVRON_DOWN_GLYPH = "›"
private const val CHEVRON_UP_GLYPH = "⌄"
private const val SWITCH_WIDTH_DP = 46
private const val SWITCH_HEIGHT_DP = 28
private const val SWITCH_RADIUS_DP = 14
private const val SWITCH_BORDER_DP = 2
private const val SWITCH_KNOB_DP = 20
