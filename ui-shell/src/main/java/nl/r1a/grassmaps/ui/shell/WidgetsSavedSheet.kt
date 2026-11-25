package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors

/**
 * The half sheet behind the map bar's chevron (round 9 `k-half`). It holds the
 * live widgets above the saved lists, so the spoken "widgets and Saved" label
 * names what the sheet actually contains.
 *
 * The sheet adds no chrome of its own: [ShellBottomSheet] owns the scrim, the
 * handle, the close control and the one bottom-chrome reach. The hosts supply
 * both bodies as slots, so the app keeps its widget controller and saved-list
 * commands. The Settings row keeps the command the old bar's Settings button
 * carried; the canon bar itself has no Settings control.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun WidgetsSavedSheet(
    sheetTitle: String,
    widgetsTitle: String,
    savedTitle: String,
    widgetsNote: String,
    settingsLabel: String,
    closeLabel: String,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    widgets: @Composable ColumnScope.() -> Unit,
    saved: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalShellColors.current
    ShellBottomSheet(
        sheetTitle = sheetTitle,
        onDismiss = onDismiss,
        closeLabel = closeLabel,
        header = {
            BasicText(
                text = widgetsTitle,
                style = TextStyle(color = colors.ink, fontSize = TITLE_SP.sp, fontWeight = FontWeight.SemiBold),
            )
        },
        content = {
            BasicText(
                text = widgetsNote,
                style = TextStyle(color = colors.muted, fontSize = NOTE_SP.sp),
            )
            widgets()
            BasicText(
                text = savedTitle,
                style = TextStyle(color = colors.ink, fontSize = TITLE_SP.sp, fontWeight = FontWeight.SemiBold),
            )
            saved()
        },
        footer = {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(SETTINGS_ROW_DP.dp)
                        .background(colors.soft, RoundedCornerShape(SETTINGS_CORNER_DP.dp))
                        .clickable(role = Role.Button, onClick = onOpenSettings)
                        .semantics { contentDescription = settingsLabel }
                        .padding(horizontal = SETTINGS_INSET_DP.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicText(
                    text = settingsLabel,
                    style = TextStyle(color = colors.ink, fontSize = ROW_SP.sp),
                )
            }
        },
    )
}

private const val TITLE_SP = 18
private const val NOTE_SP = 13
private const val ROW_SP = 15
private const val SETTINGS_ROW_DP = 48
private const val SETTINGS_CORNER_DP = 12
private const val SETTINGS_INSET_DP = 12
