package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.pointer.pointerInput
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
private val DEFAULT_CHROME_HEIGHT =
    (HANDLE_TOP_DP + HANDLE_HEIGHT_DP + HANDLE_BOTTOM_DP + CLOSE_SIZE_DP + SHEET_SECTION_GAP_DP * 2).dp

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
 * the chrome again. A tap on the scrim dismisses the sheet.
 *
 * [closeLabel] is null when the host's own header carries the close control.
 * [chromeHeight] is the height of the host's header and footer above the
 * scrolling area, so a sheet with pinned actions can bound its own content.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun ShellBottomSheet(
    sheetTitle: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    anchor: Alignment = Alignment.BottomCenter,
    showHandle: Boolean = true,
    closeLabel: String? = null,
    chromeHeight: Dp = DEFAULT_CHROME_HEIGHT,
    header: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalShellColors.current
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.scrim)
                // The scrim is not a control: a screen reader user closes the
                // sheet with a close button instead of a full-screen button.
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
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
                if (showHandle) {
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
                Column(
                    modifier =
                        Modifier
                            .heightIn(max = contentMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(SHEET_SECTION_GAP_DP.dp),
                    content = content,
                )
                footer()
            }
        }
    }
}
