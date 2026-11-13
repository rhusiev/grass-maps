package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.ElevationChartAxis
import nl.r1a.grassmap.plugin.ui.api.ElevationChartState
import java.time.ZoneId
import java.util.Locale

/**
 * The round-10 elevation chart (T-0404), drawn from the analysis engine's
 * profile. It is read-only and sits behind a Details toggle; the axis chips
 * stay beside the chart, as the design places them.
 *
 * Colours are the validated round-10 palette: seven slope colours from the
 * steepest descent to the steepest climb on the hike scale, a hairline grid and
 * a dark line over the filled area. The light and dark palettes are two sets of
 * the same roles; the surface follows the theme so the chart reads on either.
 */
internal data class ElevationChartPalette(
    val surface: Color,
    val line: Color,
    val grid: Color,
    val label: Color,
    val slope: List<Color>,
    val chip: Color,
    val chipBorder: Color,
    val chipText: Color,
    val chipSelected: Color,
    val chipSelectedText: Color,
)

/** The validated round-10 chart palette for the given theme. */
internal fun elevationChartPalette(dark: Boolean): ElevationChartPalette = if (dark) DARK_PALETTE else LIGHT_PALETTE

private val LIGHT_PALETTE =
    ElevationChartPalette(
        surface = Color(0xFFFFFFFF),
        line = Color(0xFF202124),
        grid = Color(0x14000000),
        label = Color(0xFF686C72),
        slope =
            listOf(
                Color(0xFF0D366B),
                Color(0xFF256ABF),
                Color(0xFF6DA7EC),
                Color(0xFFD8D6D0),
                Color(0xFFEE8C80),
                Color(0xFFD9473E),
                Color(0xFF8F1D1A),
            ),
        chip = Color(0xFFFFFFFF),
        chipBorder = Color(0xFFDCD8D1),
        chipText = Color(0xFF202124),
        chipSelected = Color(0xFF202124),
        chipSelectedText = Color(0xFFFFFFFF),
    )

private val DARK_PALETTE =
    ElevationChartPalette(
        surface = Color(0xFF24282D),
        line = Color(0xFFE8EAED),
        grid = Color(0x1AFFFFFF),
        label = Color(0xFF9AA0A6),
        slope =
            listOf(
                Color(0xFFB7D3F6),
                Color(0xFF5598E7),
                Color(0xFF1F5AA3),
                Color(0xFF7C7F84),
                Color(0xFFB03A34),
                Color(0xFFEE6F65),
                Color(0xFFFFC4BC),
            ),
        chip = Color(0x00000000),
        chipBorder = Color(0xFF4A4F55),
        chipText = Color(0xFFE8EAED),
        chipSelected = Color(0xFFFFFFFF),
        chipSelectedText = Color(0xFF202124),
    )

/**
 * The chart card: the title and axis chips above the plot, nothing else. A
 * profile with no drawable samples draws nothing at all.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun ElevationChart(
    state: ElevationChartState,
    strings: Strings,
    dark: Boolean,
    onAxis: (ElevationChartAxis) -> Unit,
    modifier: Modifier = Modifier,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val content = remember(state, strings, locale, zone) { elevationChartContent(state, strings, locale, zone) }
    if (content == null) {
        return
    }
    val palette = elevationChartPalette(dark)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(palette.surface, RoundedCornerShape(CHART_CORNER_DP.dp))
                .padding(CHART_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(CHART_GAP_DP.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CHIP_GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = content.title.uppercase(locale),
                style =
                    TextStyle(
                        color = palette.label,
                        fontSize = TITLE_FONT_SP.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = TITLE_LETTER_SPACING_SP.sp,
                    ),
            )
            Box(Modifier.weight(1f))
            content.axes.forEach { chip ->
                ElevationAxisChip(
                    chip = chip,
                    palette = palette,
                    onClick = { onAxis(chip.axis) },
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .width(Y_LABEL_WIDTH_DP.dp)
                        .height(PLOT_HEIGHT_DP.dp)
                        .padding(end = LABEL_GAP_DP.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                content.yTicks.forEach { tick ->
                    BasicText(text = tick.label, style = TextStyle(color = palette.label, fontSize = LABEL_FONT_SP.sp))
                }
            }
            Canvas(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(PLOT_HEIGHT_DP.dp)
                        .semantics { contentDescription = content.title },
            ) {
                val width = size.width
                val height = size.height
                content.yTicks.forEach { tick ->
                    val y = tick.fraction * height
                    drawLine(
                        color = palette.grid,
                        start = Offset(0f, y),
                        end = Offset(width, y),
                        strokeWidth = GRID_STROKE_PX,
                    )
                }
                content.slopes.forEach { slope ->
                    val colour = palette.slope[slope.bin.coerceIn(0, palette.slope.lastIndex)]
                    val fill =
                        Path().apply {
                            moveTo(slope.startFraction * width, height)
                            lineTo(slope.startFraction * width, slope.startY * height)
                            lineTo(slope.endFraction * width, slope.endY * height)
                            lineTo(slope.endFraction * width, height)
                            close()
                        }
                    drawPath(fill, colour)
                }
                val line =
                    Path().apply {
                        content.line.forEachIndexed { index, point ->
                            if (index == 0) {
                                moveTo(point.x * width, point.y * height)
                            } else {
                                lineTo(point.x * width, point.y * height)
                            }
                        }
                    }
                drawPath(line, palette.line, style = Stroke(width = LINE_STROKE_PX))
            }
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = Y_LABEL_WIDTH_DP.dp + LABEL_GAP_DP.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            content.xTicks.forEach { tick ->
                BasicText(text = tick.label, style = TextStyle(color = palette.label, fontSize = LABEL_FONT_SP.sp))
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ElevationAxisChip(
    chip: ElevationChartAxisChip,
    palette: ElevationChartPalette,
    onClick: () -> Unit,
) {
    val background = if (chip.selected) palette.chipSelected else palette.chip
    val text = if (chip.selected) palette.chipSelectedText else palette.chipText
    BasicText(
        text = chip.label,
        style = TextStyle(color = text, fontSize = CHIP_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
        modifier =
            Modifier
                .border(CHIP_BORDER_DP.dp, if (chip.selected) palette.chipSelected else palette.chipBorder, CHIP_SHAPE)
                .background(background, CHIP_SHAPE)
                .clickable(onClick = onClick)
                .padding(horizontal = CHIP_PADDING_HORIZONTAL_DP.dp, vertical = CHIP_PADDING_VERTICAL_DP.dp),
    )
}

/**
 * The row that opens and closes the analysis. The chart is behind it, so the
 * panel keeps its compact shape until the user asks for the details.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun AnalysisDetailsToggle(
    expanded: Boolean,
    strings: Strings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colour: Color = Color.White,
) {
    val key = if (expanded) DETAILS_HIDE_KEY else DETAILS_SHOW_KEY
    val glyph = if (expanded) COLLAPSE_GLYPH else EXPAND_GLYPH
    BasicText(
        text = "$glyph  ${strings.resolve(key)}",
        style = TextStyle(color = colour, fontSize = DETAILS_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
        modifier = modifier.clickable(onClick = onClick),
    )
}

private const val DETAILS_SHOW_KEY = "elevation_chart_details_show"
private const val DETAILS_HIDE_KEY = "elevation_chart_details_hide"
private const val EXPAND_GLYPH = "\u25BE"
private const val COLLAPSE_GLYPH = "\u25B4"

private const val CHART_CORNER_DP = 12
private const val CHART_PADDING_DP = 10
private const val CHART_GAP_DP = 6
private const val PLOT_HEIGHT_DP = 140
private const val Y_LABEL_WIDTH_DP = 34
private const val LABEL_GAP_DP = 6
private const val TITLE_FONT_SP = 11
private const val TITLE_LETTER_SPACING_SP = 0.5f
private const val LABEL_FONT_SP = 10
private const val CHIP_FONT_SP = 12
private const val CHIP_GAP_DP = 6
private const val CHIP_BORDER_DP = 1
private const val CHIP_PADDING_HORIZONTAL_DP = 10
private const val CHIP_PADDING_VERTICAL_DP = 5
private const val DETAILS_FONT_SP = 14
private const val GRID_STROKE_PX = 1f
private const val LINE_STROKE_PX = 1.6f

private val CHIP_SHAPE = RoundedCornerShape(20.dp)
