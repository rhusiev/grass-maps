package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.model.TrackFollowPlan
import nl.r1a.grassmap.plugin.api.model.TrackFollowStart
import nl.r1a.grassmap.plugin.api.service.Strings
import java.util.Locale
import kotlin.math.roundToLong

// The follow-a-track start sheet (T-0412, design round 6 `t-follow-start`): the
// track's name and size, the three places following can begin, and Start.
//
// From the nearest point is selected when the sheet opens, because a tap on a
// track usually happens where the user is standing and sending them to the
// line's first point would walk them back over ground they have already
// covered. The other two choices are one tap away, so a loop can be walked the
// other way or started at its first point without editing the track.
//
// The rows are the plan's own options in the plan's order, and a hint is drawn
// only when the position was known: the sheet never invents a distance.

/** One choice in the start sheet: its label, the walk to its join, and its state. */
data class FollowTrackStartRow(
    val start: TrackFollowStart,
    val label: String,
    val hint: String?,
    val selected: Boolean,
)

/** The start sheet's text, ready to draw. */
data class FollowTrackSheetDisplay(
    val title: String,
    val subtitle: String,
    val rows: List<FollowTrackStartRow>,
    val startLabel: String,
    val closeLabel: String,
)

/**
 * The sheet's text for one plan. [distanceMeters] and [ascentMeters] are the
 * analysis engine's totals for the whole track, so a sheet opened from the
 * track card repeats the numbers the card already showed.
 */
fun followTrackSheetDisplay(
    plan: TrackFollowPlan,
    name: String?,
    distanceMeters: Double,
    ascentMeters: Double,
    selected: TrackFollowStart,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
): FollowTrackSheetDisplay {
    val trackName = name?.takeIf { it.isNotBlank() } ?: strings.resolve(FOLLOW_UNTITLED_KEY)
    val subtitleParts = mutableListOf(distanceText(distanceMeters, strings, locale))
    val ascent = ascentMeters.roundToLong()
    if (ascent > 0L) {
        subtitleParts += strings.resolve(FOLLOW_ASCENT_KEY, listOf(ascent.toString()))
    }
    return FollowTrackSheetDisplay(
        title = strings.resolve(FOLLOW_TITLE_KEY, listOf(trackName)),
        subtitle = subtitleParts.joinToString(META_SEPARATOR),
        rows =
            plan.options.map { option ->
                FollowTrackStartRow(
                    start = option.start,
                    label = strings.resolve(labelKey(option.start)),
                    hint =
                        option.joinDistanceMeters?.let { distance ->
                            val key =
                                if (option.start == TrackFollowStart.Reversed) {
                                    FOLLOW_LOOP_HINT_KEY
                                } else {
                                    FOLLOW_HINT_KEY
                                }
                            strings.resolve(key, listOf(distanceText(distance, strings, locale)))
                        },
                    selected = option.start == selected,
                )
            },
        startLabel = strings.resolve(FOLLOW_START_KEY),
        closeLabel = strings.resolve(FOLLOW_CLOSE_KEY),
    )
}

private fun labelKey(start: TrackFollowStart): String =
    when (start) {
        TrackFollowStart.Start -> FOLLOW_FROM_START_KEY
        TrackFollowStart.Reversed -> FOLLOW_FROM_END_KEY
        TrackFollowStart.Nearest -> FOLLOW_FROM_NEAREST_KEY
    }

/**
 * The sheet over the map: a scrim that dismisses it, the three choices as
 * selectable rows and the Start button.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun FollowTrackSheet(
    display: FollowTrackSheetDisplay,
    dark: Boolean,
    onSelect: (TrackFollowStart) -> Unit,
    onStart: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = trackLibraryPalette(dark)
    Box(modifier = modifier.fillMaxSize()) {
        val scrimInteraction = remember { MutableInteractionSource() }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(SCRIM_COLOR)
                    .clickable(
                        interactionSource = scrimInteraction,
                        indication = null,
                        onClick = onClose,
                    ),
        )
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .widthIn(max = SHEET_MAX_WIDTH_DP.dp)
                    .background(colors.surface, RoundedCornerShape(SHEET_CORNER_DP.dp))
                    .navigationBarsPadding()
                    .padding(horizontal = SHEET_PADDING_DP.dp)
                    .padding(bottom = SHEET_PADDING_DP.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .padding(top = HANDLE_TOP_DP.dp, bottom = HANDLE_BOTTOM_DP.dp)
                        .align(Alignment.CenterHorizontally)
                        .width(HANDLE_WIDTH_DP.dp)
                        .height(HANDLE_HEIGHT_DP.dp)
                        .background(colors.textSecondary, CircleShape),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = display.title,
                        style =
                            TextStyle(
                                color = colors.textPrimary,
                                fontSize = TITLE_FONT_SP.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                    BasicText(
                        text = display.subtitle,
                        style = TextStyle(color = colors.textSecondary, fontSize = SUBTITLE_FONT_SP.sp),
                    )
                }
                BasicText(
                    text = CLOSE_GLYPH,
                    style = TextStyle(color = colors.textPrimary, fontSize = CONTROL_FONT_SP.sp),
                    modifier =
                        Modifier
                            .semantics { contentDescription = display.closeLabel }
                            .background(colors.field, CircleShape)
                            .clickable(onClick = onClose)
                            .padding(CLOSE_PADDING_DP.dp),
                )
            }
            Column(modifier = Modifier.padding(top = ROW_LIST_TOP_DP.dp)) {
                display.rows.forEach { row ->
                    StartRow(row = row, colors = colors, onSelect = onSelect)
                }
            }
            BasicText(
                text = display.startLabel,
                style =
                    TextStyle(
                        color = Color.White,
                        fontSize = START_FONT_SP.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                modifier =
                    Modifier
                        .padding(top = START_TOP_DP.dp)
                        .fillMaxWidth()
                        .background(colors.accent, RoundedCornerShape(START_CORNER_DP.dp))
                        .clickable(onClick = onStart)
                        .padding(vertical = START_PADDING_VERTICAL_DP.dp),
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun StartRow(
    row: FollowTrackStartRow,
    colors: TrackLibraryPalette,
    onSelect: (TrackFollowStart) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = ROW_SPACING_DP.dp)
                .background(
                    if (row.selected) colors.selection else Color.Transparent,
                    RoundedCornerShape(ROW_CORNER_DP.dp),
                ).selectable(
                    selected = row.selected,
                    role = Role.RadioButton,
                    onClick = { onSelect(row.start) },
                ).padding(horizontal = ROW_PADDING_HORIZONTAL_DP.dp, vertical = ROW_PADDING_VERTICAL_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_ICON_GAP_DP.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(ROW_ICON_DP.dp)
                    .background(colors.selection, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            StartGlyph(start = row.start, color = colors.accent)
        }
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = row.label,
                style =
                    TextStyle(
                        color = colors.textPrimary,
                        fontSize = ROW_LABEL_FONT_SP.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
            )
            row.hint?.let { hint ->
                BasicText(
                    text = hint,
                    style = TextStyle(color = colors.textSecondary, fontSize = ROW_HINT_FONT_SP.sp),
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .size(RADIO_DP.dp)
                    .semantics { contentDescription = row.label },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f - RADIO_BORDER / 2f
                drawCircle(
                    color = if (row.selected) colors.accent else colors.textSecondary,
                    radius = radius,
                    style = Stroke(width = RADIO_BORDER),
                )
                if (row.selected) {
                    drawCircle(color = colors.accent, radius = size.minDimension * RADIO_DOT_RATIO)
                }
            }
        }
    }
}

/**
 * The choice's mark, drawn from the design's three icons: a flag for the
 * line's start, two arrows for the reversed line and a crosshair for the
 * nearest point.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun StartGlyph(
    start: TrackFollowStart,
    color: Color,
) {
    Canvas(Modifier.size(GLYPH_DP.dp)) {
        val stroke = Stroke(width = size.minDimension * GLYPH_STROKE_RATIO)
        when (start) {
            TrackFollowStart.Start -> {
                val poleX = size.width * FLAG_POLE_X
                drawLine(
                    color = color,
                    start = Offset(poleX, size.height * FLAG_TOP_Y),
                    end = Offset(poleX, size.height * FLAG_BOTTOM_Y),
                    strokeWidth = stroke.width,
                )
                val flag =
                    Path().apply {
                        moveTo(poleX, size.height * FLAG_TOP_Y)
                        lineTo(size.width * FLAG_TIP_X, size.height * FLAG_MID_Y)
                        lineTo(poleX, size.height * FLAG_BOTTOM_FLAG_Y)
                        close()
                    }
                drawPath(flag, color)
            }

            TrackFollowStart.Reversed -> {
                val leftX = size.width * REVERSED_LEFT_X
                val rightX = size.width * REVERSED_RIGHT_X
                val topY = size.height * REVERSED_TOP_Y
                val bottomY = size.height * REVERSED_BOTTOM_Y
                val head = size.minDimension * ARROW_HEAD_RATIO
                drawLine(color, Offset(leftX, topY), Offset(leftX, bottomY), stroke.width)
                drawLine(color, Offset(leftX, bottomY), Offset(leftX - head, bottomY - head), stroke.width)
                drawLine(color, Offset(leftX, bottomY), Offset(leftX + head, bottomY - head), stroke.width)
                drawLine(color, Offset(rightX, topY), Offset(rightX, bottomY), stroke.width)
                drawLine(color, Offset(rightX, topY), Offset(rightX - head, topY + head), stroke.width)
                drawLine(color, Offset(rightX, topY), Offset(rightX + head, topY + head), stroke.width)
            }

            TrackFollowStart.Nearest -> {
                val centre = Offset(size.width / 2f, size.height / 2f)
                drawCircle(color = color, radius = size.minDimension * NEAREST_RADIUS_RATIO, center = centre, style = stroke)
                drawCircle(color = color, radius = size.minDimension * NEAREST_DOT_RATIO, center = centre)
                val tick = size.minDimension * NEAREST_TICK_RATIO
                listOf(
                    Offset(centre.x, 0f) to Offset(centre.x, tick),
                    Offset(centre.x, size.height) to Offset(centre.x, size.height - tick),
                    Offset(0f, centre.y) to Offset(tick, centre.y),
                    Offset(size.width, centre.y) to Offset(size.width - tick, centre.y),
                ).forEach { (from, to) -> drawLine(color, from, to, stroke.width) }
            }
        }
    }
}

private const val FOLLOW_TITLE_KEY = "follow_track_title"
private const val FOLLOW_UNTITLED_KEY = "follow_track_untitled"
private const val FOLLOW_ASCENT_KEY = "follow_track_ascent"
private const val FOLLOW_FROM_START_KEY = "follow_track_from_start"
private const val FOLLOW_FROM_END_KEY = "follow_track_from_end_reversed"
private const val FOLLOW_FROM_NEAREST_KEY = "follow_track_from_nearest"
private const val FOLLOW_HINT_KEY = "follow_track_to_get_there"
private const val FOLLOW_LOOP_HINT_KEY = "follow_track_to_get_there_loop"
private const val FOLLOW_START_KEY = "follow_track_start"
private const val FOLLOW_CLOSE_KEY = "follow_track_close"

private const val META_SEPARATOR = " · "
private const val CLOSE_GLYPH = "✕"

private const val SHEET_MAX_WIDTH_DP = 560
private const val SHEET_CORNER_DP = 22
private const val SHEET_PADDING_DP = 16
private const val TITLE_FONT_SP = 19
private const val SUBTITLE_FONT_SP = 13
private const val CONTROL_FONT_SP = 14
private const val CLOSE_PADDING_DP = 8
private const val HANDLE_WIDTH_DP = 36
private const val HANDLE_HEIGHT_DP = 4
private const val HANDLE_TOP_DP = 9
private const val HANDLE_BOTTOM_DP = 12
private const val ROW_LIST_TOP_DP = 8
private const val ROW_SPACING_DP = 2
private const val ROW_CORNER_DP = 14
private const val ROW_PADDING_HORIZONTAL_DP = 10
private const val ROW_PADDING_VERTICAL_DP = 9
private const val ROW_ICON_GAP_DP = 10
private const val ROW_ICON_DP = 36
private const val ROW_LABEL_FONT_SP = 15
private const val ROW_HINT_FONT_SP = 13
private const val RADIO_DP = 22
private const val RADIO_DOT_RATIO = 0.22f
private const val RADIO_BORDER = 2f
private const val START_TOP_DP = 10
private const val START_CORNER_DP = 25
private const val START_PADDING_VERTICAL_DP = 14
private const val START_FONT_SP = 16
private const val GLYPH_DP = 20
private const val GLYPH_STROKE_RATIO = 0.12f

private const val FLAG_POLE_X = 0.25f
private const val FLAG_TOP_Y = 0.15f
private const val FLAG_MID_Y = 0.42f
private const val FLAG_BOTTOM_FLAG_Y = 0.68f
private const val FLAG_TIP_X = 0.8f
private const val FLAG_BOTTOM_Y = 0.9f

private const val REVERSED_LEFT_X = 0.32f
private const val REVERSED_RIGHT_X = 0.68f
private const val REVERSED_TOP_Y = 0.12f
private const val REVERSED_BOTTOM_Y = 0.88f
private const val ARROW_HEAD_RATIO = 0.18f

private const val NEAREST_RADIUS_RATIO = 0.3f
private const val NEAREST_DOT_RATIO = 0.1f
private const val NEAREST_TICK_RATIO = 0.12f

private val SCRIM_COLOR = Color(0x52202124)
