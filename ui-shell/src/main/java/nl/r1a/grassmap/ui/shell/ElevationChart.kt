package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.ElevationChartAxis
import nl.r1a.grassmap.plugin.ui.api.ElevationChartRange
import nl.r1a.grassmap.plugin.ui.api.ElevationChartState
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The round-10 elevation chart (T-0404), drawn from the analysis engine's
 * profile. It sits behind a Details toggle; the axis chips stay beside the
 * chart, as the design places them.
 *
 * With [onRange] the chart is also the range selector (T-0405): a drag across
 * the plot reports the selected distance range, and the chart draws the band
 * and its two handles for the range in [ElevationChartState.selection]. A
 * chart without [onRange] stays read-only, as the route preview's does.
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
    val bandFill: Color,
    val bandBorder: Color,
    val bandHandle: Color,
)

/** The validated round-10 chart palette for the given theme. */
internal fun elevationChartPalette(dark: Boolean): ElevationChartPalette = if (dark) DARK_PALETTE else LIGHT_PALETTE

/**
 * The round-10 surface and way-type colours (T-0406), in the given theme.
 *
 * Ground and Path are the same green and Gravel and Track the same amber in both
 * themes, because the design shares one colour per material and one per way
 * class rather than inventing a fifth one. Steps has no entry: it is drawn as
 * the design's stripes, not as a colour.
 */
internal fun elevationStripColour(
    band: ElevationStripBand,
    dark: Boolean,
): Color = (if (dark) DARK_STRIP_BANDS else LIGHT_STRIP_BANDS).getValue(band)

/** The colour of the design's 45° stripes, light or dark (T-0406). */
internal fun elevationStripStripeColour(dark: Boolean): Color = if (dark) STRIPE_DARK else STRIPE_LIGHT

/** The tones of the percentages block under the strips (T-0406). */
internal data class ElevationStripSummaryPalette(
    val surface: Color,
    val label: Color,
    val value: Color,
)

/** The percentages block's tones; the design draws it in both themes (T-0406). */
internal fun elevationStripSummaryPalette(dark: Boolean): ElevationStripSummaryPalette = if (dark) DARK_SUMMARY else LIGHT_SUMMARY

private val LIGHT_STRIP_BANDS =
    mapOf(
        ElevationStripBand.Asphalt to Color(0xFF4A3AA7),
        ElevationStripBand.Paving to Color(0xFFE87BA4),
        ElevationStripBand.Ground to Color(0xFF008300),
        ElevationStripBand.Gravel to Color(0xFFEDA100),
        ElevationStripBand.Street to Color(0xFF2A78D6),
        ElevationStripBand.Footway to Color(0xFFE87BA4),
        ElevationStripBand.Path to Color(0xFF008300),
        ElevationStripBand.Track to Color(0xFFEDA100),
    )

private val DARK_STRIP_BANDS =
    mapOf(
        ElevationStripBand.Asphalt to Color(0xFF9085E9),
        ElevationStripBand.Paving to Color(0xFFD55181),
        ElevationStripBand.Ground to Color(0xFF008300),
        ElevationStripBand.Gravel to Color(0xFFC98500),
        ElevationStripBand.Street to Color(0xFF3987E5),
        ElevationStripBand.Footway to Color(0xFFD55181),
        ElevationStripBand.Path to Color(0xFF008300),
        ElevationStripBand.Track to Color(0xFFC98500),
    )

private val STRIPE_LIGHT = Color(0xFF202124)
private val STRIPE_DARK = Color(0xFFE8EAED)

private val LIGHT_SUMMARY =
    ElevationStripSummaryPalette(
        surface = Color(0xFFF2F1EE),
        label = Color(0xFF686C72),
        value = Color(0xFF202124),
    )

private val DARK_SUMMARY =
    ElevationStripSummaryPalette(
        surface = Color(0xFF2F343A),
        label = Color(0xFF9AA0A6),
        value = Color(0xFFE8EAED),
    )

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
        bandFill = Color(0x14202124),
        bandBorder = Color(0x80202124),
        bandHandle = Color(0xFF202124),
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
        bandFill = Color(0x1FFFFFFF),
        bandBorder = Color(0x80E8EAED),
        bandHandle = Color(0xFFE8EAED),
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
    onRange: ((ElevationChartRange?) -> Unit)? = null,
) {
    val content = remember(state, strings, locale, zone) { elevationChartContent(state, strings, locale, zone) }
    if (content == null) {
        return
    }
    val palette = elevationChartPalette(dark)
    val latestState by rememberUpdatedState(state)
    val latestOnRange by rememberUpdatedState(onRange)
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
            val drag =
                if (onRange == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        var anchor: Float? = null
                        detectDragGestures(
                            onDragStart = { offset ->
                                anchor = (offset.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragCancel = { anchor = null },
                            onDragEnd = { anchor = null },
                            onDrag = { change, _ ->
                                val start = anchor ?: return@detectDragGestures
                                change.consume()
                                val callback = latestOnRange ?: return@detectDragGestures
                                val selected = selectedRange(latestState, start, change.position.x / size.width)
                                if (selected != null) {
                                    callback(selected)
                                }
                            },
                        )
                    }
                }
            Canvas(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(PLOT_HEIGHT_DP.dp)
                        .semantics { contentDescription = content.title }
                        .then(drag),
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
                content.band?.let { band -> drawBand(band, palette, width, height) }
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
        val strips = remember(state, strings, locale) { elevationStripRows(state, strings, locale) }
        if (strips.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(STRIP_ROW_GAP_DP.dp)) {
                strips.forEach { strip ->
                    StripRow(row = strip, dark = dark, palette = palette, locale = locale)
                }
                StripSummary(rows = strips, dark = dark)
            }
        }
    }
}

/**
 * One strip: the class title, its legend, and the strip itself drawn over the
 * chart's own plot width so a boundary sits under the elevation it ends.
 *
 * A piece shorter than the design's 2 px gap is not drawn; the legend still
 * counts it, because the percentage comes from the distance, not from the
 * pixels.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun StripRow(
    row: ElevationStripRow,
    dark: Boolean,
    palette: ElevationChartPalette,
    locale: Locale,
) {
    Column(verticalArrangement = Arrangement.spacedBy(STRIP_LEGEND_GAP_DP.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = row.title.uppercase(locale),
                modifier = Modifier.width(STRIP_TITLE_WIDTH_DP.dp),
                style =
                    TextStyle(
                        color = palette.label,
                        fontSize = STRIP_TITLE_FONT_SP.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = TITLE_LETTER_SPACING_SP.sp,
                    ),
            )
            StripLegend(entries = row.legend, dark = dark, palette = palette, modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(Y_LABEL_WIDTH_DP.dp + LABEL_GAP_DP.dp))
            Canvas(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(STRIP_HEIGHT_DP.dp)
                        .clip(RoundedCornerShape(STRIP_CORNER_DP.dp))
                        .semantics { contentDescription = row.title },
            ) {
                val gap = STRIP_SEGMENT_GAP_DP.dp.toPx()
                row.pieces.forEachIndexed { index, piece ->
                    val start = piece.startFraction * size.width
                    val end =
                        (piece.endFraction * size.width).let { boundary ->
                            if (index == row.pieces.lastIndex) boundary else boundary - gap
                        }
                    if (end - start > 0f) {
                        drawStripPiece(piece = piece, start = start, end = end, dark = dark, palette = palette)
                    }
                }
            }
        }
    }
}

/** The legend: one swatch and one label per class that occurs, in distance order. */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("ktlint:standard:function-naming")
@Composable
private fun StripLegend(
    entries: List<ElevationStripLegendEntry>,
    dark: Boolean,
    palette: ElevationChartPalette,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LEGEND_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(LEGEND_ROW_GAP_DP.dp),
    ) {
        entries.forEach { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LEGEND_SWATCH_GAP_DP.dp),
            ) {
                Canvas(
                    modifier =
                        Modifier
                            .size(LEGEND_SWATCH_DP.dp)
                            .clip(RoundedCornerShape(LEGEND_SWATCH_CORNER_DP.dp)),
                ) {
                    if (entry.band.isStriped) {
                        drawStripedSurface(palette.surface, elevationStripStripeColour(dark), 0f, size.width)
                    } else {
                        drawRect(elevationStripColour(entry.band, dark))
                    }
                }
                BasicText(
                    text = entry.label,
                    style = TextStyle(color = palette.label, fontSize = LEGEND_FONT_SP.sp),
                )
            }
        }
    }
}

/** One piece of a strip: its colour, or the design's stripes on the chart's surface. */
private fun DrawScope.drawStripPiece(
    piece: ElevationStripPiece,
    start: Float,
    end: Float,
    dark: Boolean,
    palette: ElevationChartPalette,
) {
    clipRect(left = start, top = 0f, right = end, bottom = size.height) {
        if (piece.striped) {
            drawStripedSurface(palette.surface, elevationStripStripeColour(dark), start, end)
        } else {
            drawRect(
                color = elevationStripColour(piece.band, dark),
                topLeft = Offset(start, 0f),
                size = Size(end - start, size.height),
            )
        }
    }
}

/** The design's 45° stripes: a 4 dp period with a 1.6 dp stroke, over [surface]. */
private fun DrawScope.drawStripedSurface(
    surface: Color,
    stripe: Color,
    start: Float,
    end: Float,
) {
    drawRect(
        color = surface,
        topLeft = Offset(start, 0f),
        size = Size(end - start, size.height),
    )
    val height = size.height
    val period = STRIPE_PERIOD_DP.dp.toPx()
    val stroke = STRIPE_STROKE_DP.dp.toPx()
    var x = start - height
    while (x < end) {
        drawLine(
            color = stripe,
            start = Offset(x, height),
            end = Offset(x + height, 0f),
            strokeWidth = stroke,
        )
        x += period
    }
}

/** The percentages block under the two strips, as the round-10 route details draws it. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun StripSummary(
    rows: List<ElevationStripRow>,
    dark: Boolean,
) {
    val palette = elevationStripSummaryPalette(dark)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(palette.surface, RoundedCornerShape(STRIP_SUMMARY_CORNER_DP.dp))
                .padding(horizontal = STRIP_SUMMARY_PADDING_HORIZONTAL_DP.dp, vertical = STRIP_SUMMARY_PADDING_VERTICAL_DP.dp),
        verticalArrangement = Arrangement.spacedBy(STRIP_SUMMARY_LINE_GAP_DP.dp),
    ) {
        rows.forEach { row ->
            BasicText(
                text =
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = palette.value, fontWeight = FontWeight.Bold)) {
                            append(row.title)
                        }
                        append(STRIP_SEPARATOR)
                        append(row.summary)
                    },
                style = TextStyle(color = palette.label, fontSize = STRIP_SUMMARY_FONT_SP.sp),
            )
        }
    }
}

/**
 * The selected range's distance when a drag runs from [anchor] to [current],
 * both fractions across the plot. Null when the drag is too short to be a
 * range, which leaves the previous selection alone.
 */
private fun selectedRange(
    state: ElevationChartState,
    anchor: Float,
    current: Float,
): ElevationChartRange? {
    val low = min(anchor.coerceIn(0f, 1f), current.coerceIn(0f, 1f))
    val high = max(anchor.coerceIn(0f, 1f), current.coerceIn(0f, 1f))
    if (high - low < MINIMUM_RANGE_SHARE) {
        return null
    }
    val start = elevationDistanceAt(state, low) ?: return null
    val end = elevationDistanceAt(state, high) ?: return null
    if (end - start <= 0.0) {
        return null
    }
    return ElevationChartRange(startMeters = start, endMeters = end)
}

/** The design's selection band: a faint fill, a dashed edge and a handle at each end. */
private fun DrawScope.drawBand(
    band: ElevationChartBand,
    palette: ElevationChartPalette,
    width: Float,
    height: Float,
) {
    val left = (band.startFraction * width).coerceIn(0f, width)
    val right = (band.endFraction * width).coerceIn(0f, width)
    if (right - left <= 0f) {
        return
    }
    drawRect(
        color = palette.bandFill,
        topLeft = Offset(left, 0f),
        size = Size(right - left, height),
    )
    drawRect(
        color = palette.bandBorder,
        topLeft = Offset(left, 0f),
        size = Size(right - left, height),
        style =
            Stroke(
                width = BAND_BORDER_DP.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_ON_DP.dp.toPx(), DASH_OFF_DP.dp.toPx())),
            ),
    )
    val handleWidth = HANDLE_WIDTH_DP.dp.toPx()
    val handleHeight = HANDLE_HEIGHT_DP.dp.toPx()
    val top = (height - handleHeight) / 2f
    listOf(left, right).forEach { x ->
        drawRoundRect(
            color = palette.bandHandle,
            topLeft = Offset((x - handleWidth / 2f).coerceIn(0f, width - handleWidth), top),
            size = Size(handleWidth, handleHeight),
            cornerRadius = CornerRadius(handleWidth / 2f),
        )
    }
}

/** The inverse contrast choice for a climb badge's number. */
internal fun elevationBadgeInk(background: Color): Color =
    if (contrastRatio(background, BADGE_INK_DARK) >= contrastRatio(background, BADGE_INK_LIGHT)) {
        BADGE_INK_DARK
    } else {
        BADGE_INK_LIGHT
    }

private fun contrastRatio(
    first: Color,
    second: Color,
): Double {
    val a = relativeLuminance(first)
    val b = relativeLuminance(second)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}

private fun relativeLuminance(color: Color): Double =
    LUMA_RED * linear(color.red) + LUMA_GREEN * linear(color.green) + LUMA_BLUE * linear(color.blue)

private fun linear(channel: Float): Double {
    val value = channel.toDouble()
    return if (value <= LINEAR_THRESHOLD) value / LINEAR_SCALE else ((value + LINEAR_OFFSET) / LINEAR_DIVISOR).pow(GAMMA)
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
private const val BAND_BORDER_DP = 1f
private const val DASH_ON_DP = 3f
private const val DASH_OFF_DP = 2f
private const val HANDLE_WIDTH_DP = 6f
private const val HANDLE_HEIGHT_DP = 18f

private const val STRIP_ROW_GAP_DP = 8
private const val STRIP_LEGEND_GAP_DP = 3
private const val STRIP_TITLE_WIDTH_DP = 62
private const val STRIP_TITLE_FONT_SP = 11
private const val STRIP_HEIGHT_DP = 12
private const val STRIP_CORNER_DP = 6
private const val STRIP_SEGMENT_GAP_DP = 2
private const val STRIPE_PERIOD_DP = 4f
private const val STRIPE_STROKE_DP = 1.6f
private const val LEGEND_GAP_DP = 8
private const val LEGEND_ROW_GAP_DP = 2
private const val LEGEND_SWATCH_DP = 7
private const val LEGEND_SWATCH_CORNER_DP = 2
private const val LEGEND_SWATCH_GAP_DP = 4
private const val LEGEND_FONT_SP = 11
private const val STRIP_SUMMARY_CORNER_DP = 12
private const val STRIP_SUMMARY_PADDING_HORIZONTAL_DP = 11
private const val STRIP_SUMMARY_PADDING_VERTICAL_DP = 8
private const val STRIP_SUMMARY_LINE_GAP_DP = 2
private const val STRIP_SUMMARY_FONT_SP = 10.5f

/** A drag shorter than this share of the plot is a tap, not a range. */
private const val MINIMUM_RANGE_SHARE = 0.02f

/** The two number colours a climb badge chooses between, and the sRGB constants of the choice. */
private val BADGE_INK_DARK = Color(0xFF202124)
private val BADGE_INK_LIGHT = Color(0xFFFFFFFF)
private const val LUMA_RED = 0.2126
private const val LUMA_GREEN = 0.7152
private const val LUMA_BLUE = 0.0722
private const val LINEAR_THRESHOLD = 0.03928
private const val LINEAR_SCALE = 12.92
private const val LINEAR_OFFSET = 0.055
private const val LINEAR_DIVISOR = 1.055
private const val GAMMA = 2.4

private val CHIP_SHAPE = RoundedCornerShape(20.dp)
