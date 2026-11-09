package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import nl.r1a.grassmap.plugin.api.model.FavouriteListsState
import nl.r1a.grassmap.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmap.plugin.api.service.Strings

/**
 * The lists sheet (T-0213). The plugin owns the state and the actions; this
 * shell draws rows and sends taps back. It never reads a file or a GPX.
 *
 * The first tap selects a point; a list's "Move here" then moves the selected
 * point. Recolour chips recolour the whole list at once, and Home and Work are
 * set from the point row. Sheet interactions are unexercised on a device in
 * CI; the content resolver and the action wiring are JVM-tested.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun FavouritesListsHost(
    state: FavouriteListsState,
    strings: Strings,
    onCreateList: (name: String, color: String) -> Unit,
    onMovePoint: (pointId: String, list: String) -> Unit,
    onRecolourList: (list: String, color: String) -> Unit,
    onSetSpecial: (pointId: String, special: FavouriteSpecial) -> Unit,
    onUndo: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = favouritesListsContent(state, strings)
    var selectedPoint by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(PRESET_COLOURS.first()) }

    Box(
        modifier = modifier.fillMaxSize().background(SCRIM_COLOR).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .padding(SHEET_MARGIN_DP.dp)
                    .widthIn(max = SHEET_MAX_WIDTH_DP.dp)
                    .background(SHEET_BACKGROUND, RoundedCornerShape(SHEET_CORNER_DP.dp))
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .padding(SHEET_PADDING_DP.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        ) {
            Header(content = content, onUndo = onUndo, onClose = onClose)
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
            if (content.points.isNotEmpty()) {
                SectionTitle(text = strings.resolve(POINTS_KEY))
                content.points.forEach { point ->
                    PointRow(
                        row = point,
                        selected = point.id == selectedPoint,
                        onSelect = { selectedPoint = if (selectedPoint == point.id) null else point.id },
                        onSetSpecial = { special -> onSetSpecial(point.id, special) },
                        strings = strings,
                    )
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Header(
    content: FavouritesListsContent,
    onUndo: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = content.title,
            style = TextStyle(color = TEXT_PRIMARY, fontSize = TITLE_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
            if (content.undoAvailable) {
                BasicText(
                    text = content.undo,
                    style = TextStyle(color = TEXT_SECONDARY, fontSize = ROW_FONT_SP.sp),
                    modifier = Modifier.clickable(onClick = onUndo).padding(CLOSE_PADDING_DP.dp),
                )
            }
            BasicText(
                text = CLOSE_GLYPH,
                style = TextStyle(color = TEXT_PRIMARY, fontSize = TITLE_FONT_SP.sp),
                modifier =
                    Modifier
                        .clickable(onClick = onClose)
                        .semantics { contentDescription = content.close }
                        .padding(CLOSE_PADDING_DP.dp),
            )
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
        textStyle = TextStyle(color = TEXT_PRIMARY, fontSize = ROW_FONT_SP.sp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(FIELD_BACKGROUND, RoundedCornerShape(FIELD_CORNER_DP.dp))
                .padding(FIELD_PADDING_DP.dp),
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
            Line(text = row.name, style = TextStyle(color = TEXT_PRIMARY, fontSize = ROW_FONT_SP.sp))
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
                .background(if (selected) SELECTED_BACKGROUND else Color.Transparent, RoundedCornerShape(FIELD_CORNER_DP.dp))
                .clickable(onClick = onSelect)
                .padding(FIELD_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(WEIGHT_ONE)) {
            Line(text = row.name, style = TextStyle(color = TEXT_PRIMARY, fontSize = ROW_FONT_SP.sp))
            Line(text = row.list, style = secondaryStyle())
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
            val swatch = favouriteColour(preset)?.let { argb -> Color(argb) } ?: SWATCH_UNKNOWN
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
    val swatch = if (color != null) Color(color) else SWATCH_UNKNOWN
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
        style = TextStyle(color = TEXT_PRIMARY, fontSize = ACTION_FONT_SP.sp),
        modifier =
            Modifier
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
        style = TextStyle(color = TEXT_PRIMARY, fontSize = ACTION_FONT_SP.sp),
        modifier = Modifier.clickable(onClick = onClick).padding(CLOSE_PADDING_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SectionTitle(text: String) {
    BasicText(
        text = text,
        style = TextStyle(color = TEXT_SECONDARY, fontSize = SECTION_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
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

private fun secondaryStyle(): TextStyle = TextStyle(color = TEXT_SECONDARY, fontSize = ROW_FONT_SP.sp)

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
private const val CLOSE_PADDING_DP = 4
private const val FIELD_PADDING_DP = 8
private const val FIELD_CORNER_DP = 6
private const val CHIP_SIZE_DP = 20
private const val CHIP_CORNER_DP = 4
private const val CHIP_GAP_DP = 6
private const val SWATCH_SIZE_DP = 16
private const val SWATCH_CORNER_DP = 4
private const val TITLE_FONT_SP = 20
private const val SECTION_FONT_SP = 13
private const val ROW_FONT_SP = 15
private const val ACTION_FONT_SP = 14
private const val WEIGHT_ONE = 1f

private val SCRIM_COLOR = Color(0x66000000)
private val SHEET_BACKGROUND = Color(0xF21B1B1B)
private val FIELD_BACKGROUND = Color(0xFF2B2B2B)
private val SELECTED_BACKGROUND = Color(0xFF35424F)
private val TEXT_PRIMARY = Color.White
private val TEXT_SECONDARY = Color(0xFFB8B8B8)
private val SWATCH_UNKNOWN = Color(0xFF555555)

/** Colours the sheet offers; a custom hex picker is backlog B-053. */
internal val PRESET_COLOURS: List<String> =
    listOf("#E53935", "#1E88E5", "#43A047", "#FB8C00", "#8E24AA", "#546E7A")
