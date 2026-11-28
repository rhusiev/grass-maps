package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellColors

private const val SHEET_MAX_WIDTH_DP = 560
private const val SHEET_CORNER_DP = 22
private const val SHEET_PADDING_DP = 16
private const val SHEET_CONTENT_MAX_FRACTION = 0.5f
const val SHEET_SECTION_GAP_DP = 10
private const val HANDLE_TOP_DP = 9
private const val HANDLE_BOTTOM_DP = 10
private const val HANDLE_WIDTH_DP = 36
private const val HANDLE_HEIGHT_DP = 4
private const val HANDLE_RADIUS_DP = 2
private const val CLOSE_SIZE_DP = 48
private const val CLOSE_FONT_SP = 20
private const val CLOSE_GLYPH = "×"
private const val DISMISS_DRAG_DP = 56
private const val SCROLL_TRACK_WIDTH_DP = 4
private const val SCROLL_THUMB_MIN_DP = 24
private val DEFAULT_CHROME_HEIGHT =
    (HANDLE_TOP_DP + HANDLE_HEIGHT_DP + HANDLE_BOTTOM_DP + CLOSE_SIZE_DP + SHEET_SECTION_GAP_DP * 2).dp

/**
 * The fixed height around a sheet's scrolling rows: the handle, [header],
 * [footer], the two gaps around the header, the gap before [footer] only when
 * it draws, and the foot padding. A host passes it as `chromeHeight`, so its
 * rows keep their share of the screen.
 */
fun sheetChromeHeight(
    header: Dp,
    footer: Dp,
): Dp {
    val footerGap = if (footer > 0.dp) SHEET_SECTION_GAP_DP.dp else 0.dp
    return (HANDLE_TOP_DP + HANDLE_HEIGHT_DP + HANDLE_BOTTOM_DP + SHEET_SECTION_GAP_DP * 2 + SHEET_PADDING_DP).dp +
        header +
        footer +
        footerGap
}

/**
 * The fixed height around the scrolling rows of a sheet whose handle is its
 * dismiss control and which has no header: the 48 dp handle, [footer], the two
 * gaps between them and the foot padding.
 */
fun dismissHandleChromeHeight(footer: Dp): Dp = (MapControlMetrics.TAP_TARGET_DP + SHEET_SECTION_GAP_DP * 2 + SHEET_PADDING_DP).dp + footer

/** The tallest of [stops] that fits in [max], or [max] when none fits. */
internal fun sheetScrollLimit(
    stops: List<Dp>,
    max: Dp,
): Dp = stops.filter { it <= max }.maxOrNull() ?: max

/** A scroll thumb's [top] and [length] in px along a track as tall as the scrolling rows. */
internal data class SheetScrollThumb(
    val top: Float,
    val length: Float,
)

/**
 * The thumb for rows scrolled [scrollPx] of [maxScrollPx] in a [viewportPx]
 * tall view, or null when the rows fit. Its length is the visible share of the
 * rows, at least [minLengthPx].
 */
internal fun sheetScrollThumb(
    viewportPx: Float,
    maxScrollPx: Float,
    scrollPx: Float,
    minLengthPx: Float,
): SheetScrollThumb? {
    if (maxScrollPx <= 0f || viewportPx <= 0f) return null
    val length = (viewportPx * viewportPx / (viewportPx + maxScrollPx)).coerceIn(minOf(minLengthPx, viewportPx), viewportPx)
    val top = (viewportPx - length) * (scrollPx / maxScrollPx).coerceIn(0f, 1f)
    return SheetScrollThumb(top, length)
}

/**
 * A track and thumb in the sheet's end padding while [state] can scroll, so a
 * sheet that opens on whole rows still shows that more rows lie below.
 */
private fun Modifier.sheetScrollIndicator(
    state: ScrollState,
    colors: ShellColors,
): Modifier =
    drawWithContent {
        drawContent()
        val width = SCROLL_TRACK_WIDTH_DP.dp.toPx()
        val thumb =
            sheetScrollThumb(size.height, state.maxValue.toFloat(), state.value.toFloat(), SCROLL_THUMB_MIN_DP.dp.toPx())
                ?: return@drawWithContent
        val x = size.width + (SHEET_PADDING_DP.dp.toPx() - width) / 2
        val corner = CornerRadius(width / 2)
        drawRoundRect(colors.soft, Offset(x, 0f), Size(width, size.height), corner)
        drawRoundRect(colors.muted, Offset(x, thumb.top), Size(width, thumb.length), corner)
    }

/** Whether a handle drag of [draggedPx], positive downward, is long enough to close the sheet. */
internal fun dragDismissesSheet(
    draggedPx: Float,
    thresholdPx: Float,
): Boolean = draggedPx >= thresholdPx

/**
 * The drag handle as the sheet's dismiss control, for a sheet without a close
 * button. It is a 48 dp target named [label]: a tap or a drag down by
 * [DISMISS_DRAG_DP] closes the sheet.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun SheetDismissHandle(
    label: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalShellColors.current
    val dismissDragPx = with(LocalDensity.current) { DISMISS_DRAG_DP.dp.toPx() }
    Box(
        modifier =
            modifier
                .sizeIn(minWidth = MapControlMetrics.TAP_TARGET_DP.dp, minHeight = MapControlMetrics.TAP_TARGET_DP.dp)
                .pointerInput(onDismiss) {
                    var dragged = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = { if (dragDismissesSheet(dragged, dismissDragPx)) onDismiss() },
                    ) { change, amount ->
                        change.consume()
                        dragged += amount
                    }
                }.clickable(role = Role.Button, onClick = onDismiss)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(HANDLE_WIDTH_DP.dp)
                .height(HANDLE_HEIGHT_DP.dp)
                .background(colors.handle, RoundedCornerShape(HANDLE_RADIUS_DP.dp)),
        )
    }
}

/**
 * The close control a sheet shares with its own header.
 *
 * It is a full 48 dp target, and the label is the only thing a screen reader
 * reads: the glyph is decoration.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun SheetCloseButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalShellColors.current
    Box(
        modifier =
            modifier
                .height(CLOSE_SIZE_DP.dp)
                .width(CLOSE_SIZE_DP.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = CLOSE_GLYPH,
            style = TextStyle(color = colors.ink, fontSize = CLOSE_FONT_SP.sp),
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * The one bottom-sheet chrome in the shell.
 *
 * It owns the scrim, the foot alignment, the rounded surface, the drag handle,
 * the close control, the focus request and the bottom-chrome inset. A host
 * supplies its header, its scrolling rows and its pinned footer; no host draws
 * the chrome again. A tap on the scrim dismisses the sheet. Without [scrim] the
 * sheet lies over the map without dimming it and the map above stays usable.
 * The scrolling rows yield to the footer, so a tall header or footer at a
 * large font scale never pushes the footer off the sheet.
 *
 * [closeLabel] is null when the host's own header carries the close control.
 * [handleLabel] turns the drag handle into the dismiss control instead.
 * [peekStops] are the heights where the scrolling rows may end when the sheet
 * opens, so a host never shows a row cut in half; the rest scrolls into view.
 * [chromeHeight] is the height of the host's header and footer above the
 * scrolling area, so a sheet with pinned actions can bound its own content.
 * [scrollIndicator] draws a track and thumb beside rows that can scroll.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun ShellBottomSheet(
    sheetTitle: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    anchor: Alignment = Alignment.BottomCenter,
    scrim: Boolean = true,
    showHandle: Boolean = true,
    closeLabel: String? = null,
    handleLabel: String? = null,
    peekStops: List<Dp> = emptyList(),
    chromeHeight: Dp = DEFAULT_CHROME_HEIGHT,
    scrollIndicator: Boolean = false,
    header: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalShellColors.current
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .then(
                    if (scrim) {
                        // The scrim is not a control: a screen reader user closes
                        // the sheet with a close button instead of a full-screen
                        // button.
                        Modifier
                            .background(colors.scrim)
                            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                    } else {
                        Modifier
                    },
                ),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .align(anchor)
                    .fillMaxWidth()
                    .widthIn(max = SHEET_MAX_WIDTH_DP.dp),
        ) {
            val contentMaxHeight = maxHeight * SHEET_CONTENT_MAX_FRACTION
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            Column(
                modifier =
                    Modifier
                        .heightIn(max = contentMaxHeight + chromeHeight)
                        .reportsBottomChrome(key = BOTTOM_SHEET_KEY)
                        .background(
                            colors.surface,
                            RoundedCornerShape(topStart = SHEET_CORNER_DP.dp, topEnd = SHEET_CORNER_DP.dp),
                        )
                        // A tap inside the sheet must not reach the scrim.
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                        .navigationBarsPadding()
                        .padding(horizontal = SHEET_PADDING_DP.dp)
                        .padding(bottom = SHEET_PADDING_DP.dp)
                        .focusRequester(focusRequester)
                        .focusTarget()
                        .semantics { paneTitle = sheetTitle },
                verticalArrangement = Arrangement.spacedBy(SHEET_SECTION_GAP_DP.dp),
            ) {
                if (handleLabel != null) {
                    SheetDismissHandle(
                        label = handleLabel,
                        onDismiss = onDismiss,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                } else if (showHandle) {
                    Box(
                        modifier =
                            Modifier
                                .padding(top = HANDLE_TOP_DP.dp, bottom = HANDLE_BOTTOM_DP.dp)
                                .align(Alignment.CenterHorizontally)
                                .width(HANDLE_WIDTH_DP.dp)
                                .height(HANDLE_HEIGHT_DP.dp)
                                .background(colors.handle, RoundedCornerShape(HANDLE_RADIUS_DP.dp)),
                    )
                }
                if (closeLabel != null) {
                    SheetCloseButton(
                        label = closeLabel,
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
                header()
                val scrollState = rememberScrollState()
                Column(
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .heightIn(max = sheetScrollLimit(peekStops, contentMaxHeight))
                            .then(if (scrollIndicator) Modifier.sheetScrollIndicator(scrollState, colors) else Modifier)
                            .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(SHEET_SECTION_GAP_DP.dp),
                    content = content,
                )
                footer()
            }
        }
    }
}
