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
 * The full-screen destination behind the Layers card's "More..." row.
 *
 * The map's all-profiles grid button already opens `ProfilesScreenSession`,
 * so the new row reuses that entry point and no screen is added. The theme
 * settings screen has a session too, but only its debug command opens it.
 */
const val LAYERS_MORE_ENTRY_POINT = "all_profiles"

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
        onDismiss = onDismiss,
        anchor = LAYERS_SHEET_ANCHOR,
        showHandle = LAYERS_SHEET_HAS_HANDLE,
        closeLabel = closeLabel,
        content = content,
    )
}
