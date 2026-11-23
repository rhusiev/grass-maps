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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors

private const val SHEET_MAX_WIDTH_DP = 560
private const val SHEET_CORNER_DP = 22
private const val SHEET_PADDING_DP = 16
private const val SHEET_CONTENT_MAX_FRACTION = 0.5f
private const val SECTION_GAP_DP = 10
private const val HANDLE_TOP_DP = 9
private const val HANDLE_BOTTOM_DP = 10
private const val HANDLE_WIDTH_DP = 36
private const val HANDLE_HEIGHT_DP = 4
private const val HANDLE_RADIUS_DP = 2
private const val CLOSE_SIZE_DP = 48
private const val CLOSE_FONT_SP = 20
private const val CLOSE_GLYPH = "×"
private const val SHEET_CHROME_HEIGHT_DP =
    HANDLE_TOP_DP + HANDLE_HEIGHT_DP + HANDLE_BOTTOM_DP + CLOSE_SIZE_DP + SECTION_GAP_DP * 2

/**
 * The bottom-sheet chrome a host screen fills with its own content.
 *
 * It owns the scrim, the foot alignment, the rounded surface, the handle and
 * the close button. Hosts supply only their rows, so a second sheet cannot
 * grow its own copy of the chrome. A tap on the scrim dismisses the sheet.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun ShellBottomSheet(
    sheetTitle: String,
    closeLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    anchor: Alignment = Alignment.BottomCenter,
    showHandle: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalShellColors.current
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.scrim)
                // The scrim is not a control: a screen reader user dismisses
                // the sheet with its own close button.
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
                        .heightIn(max = contentMaxHeight + SHEET_CHROME_HEIGHT_DP.dp)
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
                verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
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
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.End)
                            .height(CLOSE_SIZE_DP.dp)
                            .width(CLOSE_SIZE_DP.dp)
                            .clickable(role = Role.Button, onClick = onDismiss)
                            .semantics { contentDescription = closeLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = CLOSE_GLYPH,
                        style = TextStyle(color = colors.ink, fontSize = CLOSE_FONT_SP.sp),
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                Column(
                    modifier =
                        Modifier
                            .heightIn(max = contentMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
                    content = content,
                )
            }
        }
    }
}
