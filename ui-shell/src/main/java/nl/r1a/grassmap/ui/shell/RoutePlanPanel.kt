package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.model.RoutePlanFailure
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.service.Strings

private const val PANEL_CORNER_DP = 12
private const val PANEL_PADDING_DP = 12
private const val PANEL_MAX_WIDTH_DP = 520
private const val PANEL_ROW_GAP_DP = 8
private const val CHIP_PADDING_HORIZONTAL_DP = 10
private const val CHIP_PADDING_VERTICAL_DP = 6
private const val VALUE_FONT_SP = 16
private const val ACTION_FONT_SP = 15
private const val STATUS_FONT_SP = 13
private val PANEL_BACKGROUND = Color(0xF2202020)
private val VALUE_COLOR = Color.White
private val STATUS_COLOR = Color(0xFFBDBDBD)
private val CHIP_COLOR = Color(0xFF37474F)
private val CHIP_SELECTED_COLOR = Color(0xFF2E7D32)
private val ACTION_COLOR = Color(0xFF2E7D32)

/**
 * The planning panel (T-0302): the start/end plan, the returned alternatives,
 * the four-field preview and the Start action. The map and this card read the
 * same [RoutePlanState], so an alternative changes both or neither.
 *
 * The preview card is [routePlanPreview] and nothing else. Swap, the close
 * control and the alternative chips are controls around it, not extra preview
 * rows; later tasks add elevation and way detail elsewhere.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun RoutePlanPanel(
    state: RoutePlanState,
    strings: Strings,
    onSwap: () -> Unit,
    onSelectAlternative: (Int) -> Unit,
    onStart: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.status == RoutePlanStatus.Idle) return
    val preview = routePlanPreview(state, strings)
    Column(
        modifier =
            modifier
                .padding(PANEL_PADDING_DP.dp)
                .widthIn(max = PANEL_MAX_WIDTH_DP.dp)
                .background(PANEL_BACKGROUND, RoundedCornerShape(PANEL_CORNER_DP.dp))
                .padding(PANEL_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
    ) {
        PanelControls(
            strings = strings,
            onSwap = onSwap,
            onClose = onClose,
        )
        when (state.status) {
            RoutePlanStatus.PickingStart -> {
                StatusLine(strings.resolve(PICK_START_KEY), strings.resolve(PICK_START_KEY))
            }

            RoutePlanStatus.PickingDestination -> {
                StatusLine(strings.resolve(PICK_DESTINATION_KEY), strings.resolve(PICK_DESTINATION_KEY))
            }

            RoutePlanStatus.Planning -> {
                StatusLine(strings.resolve(PLANNING_KEY), strings.resolve(PLANNING_KEY))
            }

            RoutePlanStatus.Failed -> {
                val key =
                    when (state.failure) {
                        RoutePlanFailure.PackMissing -> PACK_MISSING_KEY
                        RoutePlanFailure.PackUnusable -> PACK_UNUSABLE_KEY
                        RoutePlanFailure.EngineUnavailable -> ENGINE_UNAVAILABLE_KEY
                        RoutePlanFailure.NoRoute, null -> FAILED_KEY
                    }
                StatusLine(strings.resolve(key), strings.resolve(key))
            }

            RoutePlanStatus.Ready -> {
                if (state.options.size > 1) {
                    AlternativeChips(
                        state = state,
                        strings = strings,
                        onSelectAlternative = onSelectAlternative,
                    )
                } else {
                    StatusLine(
                        strings.resolve(NO_ALTERNATIVES_KEY),
                        strings.resolve(NO_ALTERNATIVES_KEY),
                    )
                }
                if (preview != null) {
                    PreviewCard(preview = preview, onStart = onStart)
                }
            }

            RoutePlanStatus.Idle -> {
                Unit
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PanelControls(
    strings: Strings,
    onSwap: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier,
        horizontalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val swap = strings.resolve(SWAP_KEY)
        BasicText(
            text = SWAP_GLYPH,
            style = TextStyle(color = VALUE_COLOR, fontSize = ACTIVE_ICON_FONT_SP.sp),
            modifier =
                Modifier
                    .semantics { contentDescription = swap }
                    .clickable(onClick = onSwap)
                    .padding(SWAP_PADDING_DP.dp),
        )
        Box(Modifier.weight(1f))
        val close = strings.resolve(PANEL_CLOSE_KEY)
        BasicText(
            text = CLOSE_GLYPH,
            style = TextStyle(color = VALUE_COLOR, fontSize = ACTIVE_ICON_FONT_SP.sp),
            modifier =
                Modifier
                    .semantics { contentDescription = close }
                    .clickable(onClick = onClose)
                    .padding(SWAP_PADDING_DP.dp),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun AlternativeChips(
    state: RoutePlanState,
    strings: Strings,
    onSelectAlternative: (Int) -> Unit,
) {
    Row(
        modifier = Modifier,
        horizontalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.options.forEachIndexed { index, option ->
            val selected = index == state.selectedIndex
            val description = strings.resolve(ALTERNATIVE_DESCRIPTION_KEY, listOf((index + 1).toString()))
            BasicText(
                text = routePlanAlternativeText(option.durationSeconds, strings),
                style =
                    TextStyle(
                        color = VALUE_COLOR,
                        fontSize = STATUS_FONT_SP.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    ),
                modifier =
                    Modifier
                        .semantics { contentDescription = description }
                        .background(
                            if (selected) CHIP_SELECTED_COLOR else CHIP_COLOR,
                            RoundedCornerShape(CHIP_CORNER_DP.dp),
                        ).clickable { onSelectAlternative(index) }
                        .padding(
                            horizontal = CHIP_PADDING_HORIZONTAL_DP.dp,
                            vertical = CHIP_PADDING_VERTICAL_DP.dp,
                        ),
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PreviewCard(
    preview: RoutePreview,
    onStart: () -> Unit,
) {
    Row(
        modifier = Modifier,
        horizontalArrangement = Arrangement.spacedBy(VALUE_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ValueText(preview.time)
        ValueText(preview.distance)
        ValueText(preview.arrival)
        Box(Modifier.weight(1f))
        BasicText(
            text = preview.start,
            style =
                TextStyle(
                    color = Color.White,
                    fontSize = ACTION_FONT_SP.sp,
                    fontWeight = FontWeight.Bold,
                ),
            modifier =
                Modifier
                    .background(ACTION_COLOR, RoundedCornerShape(CHIP_CORNER_DP.dp))
                    .clickable(onClick = onStart)
                    .padding(
                        horizontal = CHIP_PADDING_HORIZONTAL_DP.dp,
                        vertical = CHIP_PADDING_VERTICAL_DP.dp,
                    ),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ValueText(text: String) {
    BasicText(
        text = text,
        style = TextStyle(color = VALUE_COLOR, fontSize = VALUE_FONT_SP.sp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun StatusLine(
    text: String,
    description: String,
) {
    BasicText(
        text = text,
        style = TextStyle(color = STATUS_COLOR, fontSize = STATUS_FONT_SP.sp),
        modifier = Modifier.semantics { contentDescription = description },
    )
}

private const val SWAP_GLYPH = "\u21C4"
private const val CLOSE_GLYPH = "\u00D7"
private const val ACTIVE_ICON_FONT_SP = 20
private const val SWAP_PADDING_DP = 4
private const val CHIP_CORNER_DP = 8
private const val VALUE_GAP_DP = 12
private const val PICK_START_KEY = "route_planner_pick_start"
private const val PICK_DESTINATION_KEY = "route_planner_pick_destination"
private const val PLANNING_KEY = "route_planner_planning"
private const val FAILED_KEY = "route_planner_failed"
private const val PACK_MISSING_KEY = "route_planner_pack_missing"
private const val PACK_UNUSABLE_KEY = "route_planner_pack_unusable"
private const val ENGINE_UNAVAILABLE_KEY = "route_planner_engine_unavailable"
private const val NO_ALTERNATIVES_KEY = "route_planner_no_alternatives"
private const val ALTERNATIVE_DESCRIPTION_KEY = "route_planner_alternative_description"
private const val SWAP_KEY = "route_planner_swap"
private const val PANEL_CLOSE_KEY = "route_planner_close"
