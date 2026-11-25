package nl.r1a.grassmaps.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.ui.api.ShellColors
import nl.r1a.grassmaps.plugin.ui.api.shellColors

private const val TITLE_SP = 21
private const val SUBTITLE_SP = 12.5f
private const val SECTION_SP = 12
private const val ROW_TITLE_SP = 14.5f
private const val CHEVRON_SP = 18

private const val HORIZONTAL_DP = 16
private const val ROW_MIN_DP = 48
private const val ROW_CORNER_DP = 10
private const val ROW_PADDING_DP = 12
private const val ROW_GAP_DP = 4
private const val SECTION_TOP_DP = 18
private const val SECTION_BOTTOM_DP = 6
private const val SUBTITLE_TOP_DP = 4
private const val BACK_DP = 48
private val BACK_GLYPH = "\u2039"
private val CHEVRON_GLYPH = "\u203a"

/**
 * The Settings index screen. A plain screen, not a sheet: the canon gives it a
 * full page with its own back header. The back chevron shares the row chevron
 * style and is centered beside the title. A search field filters the rows by
 * their resolved titles and shows an empty message when nothing matches.
 * Every row is a 48 dp press target and hands off through [onOpen]; the screen
 * keeps only the search query as its own state.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun SettingsIndexHost(
    content: SettingsIndexContent,
    dark: Boolean,
    onBack: () -> Unit,
    onOpen: (SettingsIndexTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = shellColors(dark)
    var query by remember { mutableStateOf("") }
    val visible = content.filtered(query)
    Box(modifier = modifier.fillMaxSize().background(colors.surface)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = HORIZONTAL_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(BACK_DP.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onBack)
                            .semantics { contentDescription = content.back },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = BACK_GLYPH,
                        style = TextStyle(color = colors.accent, fontSize = CHEVRON_SP.sp),
                    )
                }
                BasicText(
                    text = content.title,
                    style = TextStyle(color = colors.ink, fontSize = TITLE_SP.sp, fontWeight = FontWeight.Bold),
                )
            }
            BasicText(
                text = content.subtitle,
                style = TextStyle(color = colors.muted, fontSize = SUBTITLE_SP.sp),
                modifier = Modifier.padding(horizontal = HORIZONTAL_DP.dp).padding(top = SUBTITLE_TOP_DP.dp),
            )
            BasicText(
                text = content.search,
                style = TextStyle(color = colors.muted, fontSize = SECTION_SP.sp),
                modifier = Modifier.padding(horizontal = HORIZONTAL_DP.dp).padding(top = SECTION_TOP_DP.dp),
            )
            SettingsSearchField(content, colors, query) { query = it }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = HORIZONTAL_DP.dp),
            ) {
                if (visible.rows.isEmpty()) {
                    BasicText(
                        text = content.empty,
                        style = TextStyle(color = colors.muted, fontSize = ROW_TITLE_SP.sp),
                        modifier = Modifier.padding(top = SECTION_TOP_DP.dp),
                    )
                } else {
                    SettingsIndexGroup.entries.forEach { group ->
                        BasicText(
                            text = content.groupTitles.getValue(group),
                            style =
                                TextStyle(
                                    color = colors.muted,
                                    fontSize = SECTION_SP.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                            modifier = Modifier.padding(top = SECTION_TOP_DP.dp, bottom = SECTION_BOTTOM_DP.dp),
                        )
                        visible.rows.filter { it.group == group }.forEach { row ->
                            IndexRow(row, colors, onOpen)
                        }
                    }
                }
                Spacer(Modifier.height(ROW_MIN_DP.dp))
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SettingsSearchField(
    content: SettingsIndexContent,
    colors: ShellColors,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.ink, fontSize = ROW_TITLE_SP.sp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = HORIZONTAL_DP.dp)
                .heightIn(min = ROW_MIN_DP.dp)
                .background(colors.soft, RoundedCornerShape(ROW_CORNER_DP.dp))
                .padding(horizontal = ROW_PADDING_DP.dp)
                .semantics { contentDescription = content.search },
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun IndexRow(
    row: SettingsIndexRow,
    colors: ShellColors,
    onOpen: (SettingsIndexTarget) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_DP.dp)
                .clip(RoundedCornerShape(ROW_CORNER_DP.dp))
                .background(colors.soft)
                .clickable(role = Role.Button, onClick = { onOpen(row.target) })
                .padding(horizontal = ROW_PADDING_DP.dp, vertical = ROW_PADDING_DP.dp / 2),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text = row.title, style = TextStyle(color = colors.ink, fontSize = ROW_TITLE_SP.sp))
        BasicText(
            text = CHEVRON_GLYPH,
            style = TextStyle(color = colors.accent, fontSize = CHEVRON_SP.sp),
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
    Spacer(Modifier.height(ROW_GAP_DP.dp))
}
