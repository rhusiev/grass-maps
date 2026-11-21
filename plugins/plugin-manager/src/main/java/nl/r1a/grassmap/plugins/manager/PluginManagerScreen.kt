package nl.r1a.grassmaps.plugins.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.model.PluginStatus

/**
 * The plugin manager screen (T-0607): the switchable plugins in sections, each
 * with a switch, a search pill at the bottom and a sheet per plugin.
 *
 * It is a pure function of [statuses] (the `core.plugins.state` list) and
 * [onSetEnabled], which `app` routes to `core.plugins.set_enabled`. [resolve]
 * turns a manifest text key into text.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun PluginManagerScreen(
    statuses: List<PluginStatus>,
    resolve: (String) -> String,
    onSetEnabled: (PluginId, Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf<PluginId?>(null) }
    val sections = pluginSections(statuses, resolve, query)
    val opened =
        openId?.let { id ->
            pluginSections(statuses, resolve).flatMap { it.rows }.firstOrNull { it.status.pluginId == id }
        }

    Box(modifier = modifier.fillMaxSize().background(SURFACE)) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .imePadding()
                    .navigationBarsPadding()
                    .widthIn(max = MAX_WIDTH_DP.dp),
        ) {
            ManagerHeader(counts = pluginCounts(statuses), onClose = onClose)
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = SCREEN_PADDING_DP.dp),
                verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
            ) {
                sections.forEach { section ->
                    SectionBlock(section, onOpen = { openId = it }, onSetEnabled = onSetEnabled)
                }
                if (sections.isEmpty()) {
                    BasicText(
                        text = stringResource(R.string.plugin_manager_no_match),
                        style = TextStyle(color = MUTED, fontSize = BODY_SP.sp),
                    )
                }
            }
            SearchPill(
                query = query,
                onQueryChange = { query = it },
                filterState = pluginFilterState(query, sections),
            )
        }
        opened?.let { row ->
            PluginSheet(
                row = row,
                onSetEnabled = onSetEnabled,
                onDone = { openId = null },
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ManagerHeader(
    counts: PluginCounts,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(SCREEN_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
    ) {
        BackButton(onClick = onClose)
        Column {
            BasicText(
                text = stringResource(R.string.plugin_manager_title),
                style = TextStyle(color = INK, fontSize = TITLE_SP.sp, fontWeight = FontWeight.Bold),
            )
            BasicText(
                text = subtitle(counts),
                style = TextStyle(color = MUTED, fontSize = BODY_SP.sp),
            )
        }
    }
}

@Composable
private fun subtitle(counts: PluginCounts): String {
    val total = pluralStringResource(R.plurals.plugin_manager_count, counts.total, counts.total)
    return if (counts.off == 0) {
        total
    } else {
        stringResource(R.string.plugin_manager_subtitle_off, total, counts.off)
    }
}

/** What the filter field says it left, or null while the field holds no query. */
@Composable
private fun pluginFilterState(
    query: String,
    sections: List<PluginSection>,
): String? {
    val matches = pluginFilterMatches(query, sections) ?: return null
    return if (matches == 0) {
        stringResource(R.string.plugin_manager_no_match)
    } else {
        pluralStringResource(R.plurals.plugin_manager_count, matches, matches)
    }
}
