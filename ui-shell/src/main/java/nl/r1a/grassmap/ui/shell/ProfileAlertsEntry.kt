@file:Suppress("ktlint:standard:function-naming")

package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.AlertsPageState

private const val ENTRY_TITLE_SP = 15
private const val ENTRY_VALUE_SP = 13
private const val ENTRY_DETAIL_SP = 12
private const val ENTRY_GAP_DP = 8

/**
 * The profile editor's alerts row (T-0413): one row that opens the profile
 * alerts page, as round 8 draws it. A profile editor that exists on another
 * branch can put this row beside its other settings rows; it reads the same
 * [AlertsPageState] the page does.
 */
@Composable
fun ProfileAlertsEntry(
    state: AlertsPageState,
    strings: Strings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = profileAlertsEntryContent(state, strings)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { contentDescription = content.description }
                .clickable(onClick = onClick)
                .padding(vertical = ENTRY_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ENTRY_GAP_DP.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = content.title,
                style = TextStyle(fontSize = ENTRY_TITLE_SP.sp, fontWeight = FontWeight.SemiBold),
            )
            BasicText(
                text = content.detail,
                style = TextStyle(fontSize = ENTRY_DETAIL_SP.sp),
            )
        }
        BasicText(
            text = content.value,
            style = TextStyle(fontSize = ENTRY_VALUE_SP.sp, fontWeight = FontWeight.SemiBold),
        )
        BasicText(text = "\u203A", style = TextStyle(fontSize = 20.sp))
    }
}
