package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment

/**
 * The Layers card's sheet anchor and chrome.
 *
 * The card used to be an anchored popup at the Layers button. It is a sheet at
 * the foot of the screen now, with the same handle the other shell sheets use.
 */
val LAYERS_SHEET_ANCHOR: Alignment = Alignment.BottomCenter
const val LAYERS_SHEET_HAS_HANDLE = true

/**
 * The Layers card as a bottom sheet.
 *
 * The host supplies the plugin rows; this composable supplies only the sheet
 * chrome. The rows keep their own state and persistence.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun LayersSheet(
    paneTitle: String,
    closeLabel: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ShellBottomSheet(
        sheetTitle = paneTitle,
        closeLabel = closeLabel,
        onDismiss = onDismiss,
        anchor = LAYERS_SHEET_ANCHOR,
        showHandle = LAYERS_SHEET_HAS_HANDLE,
        content = content,
    )
}
