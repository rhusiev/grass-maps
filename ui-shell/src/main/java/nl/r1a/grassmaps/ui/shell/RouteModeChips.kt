package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
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
import nl.r1a.grassmaps.plugin.api.model.RoutePlanState
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugin.api.service.Strings

/**
 * One travel mode the engine actually computed, with that route's time. The
 * chip is informational: a mode appears only when [RoutePlanState] carries its
 * option, so no time is invented for a profile the engine did not route.
 */
data class RouteModeChip(
    val profile: RouteProfile,
    val label: String,
    val time: String,
) {
    val text: String get() = label + MODE_SEPARATOR + time
}

/**
 * The modes with a computed option. The plan publishes one profile and the
 * engine's options for that profile, so exactly one mode has a time to show.
 * The chip borrows the engine's own duration text through
 * [routePlanAlternativeText], so it agrees with the alternative cards.
 */
fun routeModeChips(
    state: RoutePlanState,
    strings: Strings,
): List<RouteModeChip> {
    val selected = state.selected ?: return emptyList()
    val profile = state.profile
    return listOf(
        RouteModeChip(
            profile = profile,
            label = profileLabel(profile, strings),
            time = routePlanAlternativeText(selected.durationSeconds, strings),
        ),
    )
}

/** One returned route as a card. Only the selected card carries the label. */
data class RouteAlternativeCard(
    val index: Int,
    val description: String,
    val selected: Boolean,
    val label: String,
)

/**
 * The engine's options in their own order. The description is the existing
 * alternative string with the option's number and its formatted time; index is
 * kept so a card reuses the planner's selection command without a second path.
 */
fun routeAlternativeCards(
    state: RoutePlanState,
    strings: Strings,
): List<RouteAlternativeCard> =
    state.options.mapIndexed { index, option ->
        RouteAlternativeCard(
            index = index,
            description =
                strings.resolve(
                    ALTERNATIVE_DESCRIPTION_KEY,
                    listOf((index + 1).toString(), routePlanAlternativeText(option.durationSeconds, strings)),
                ),
            selected = index == state.selectedIndex,
            label = if (index == state.selectedIndex) strings.resolve(SELECTED_KEY) else "",
        )
    }

/** The computed mode chips, from the same shell tokens as the planner sheet. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun RouteModeChipsRow(
    chips: List<RouteModeChip>,
    modifier: Modifier = Modifier,
) {
    if (chips.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEach { chip ->
            val shape = RoundedCornerShape(CHIP_CORNER_DP.dp)
            Box(
                modifier =
                    Modifier
                        .heightIn(min = MIN_TAP_TARGET_DP.dp)
                        .background(ROUTE_CHIP_COLOR, shape)
                        .border(1.dp, ROUTE_CONTROL_BORDER, shape)
                        .padding(horizontal = CHIP_PADDING_HORIZONTAL_DP.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = chip.text,
                    style =
                        TextStyle(
                            color = ROUTE_VALUE_COLOR,
                            fontSize = STATUS_FONT_SP.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                )
            }
        }
    }
}

/** The alternative cards. A tap selects through the planner's existing command. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun RouteAlternativeCards(
    cards: List<RouteAlternativeCard>,
    onSelectAlternative: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(CHIP_GAP_DP.dp),
    ) {
        cards.forEach { card ->
            val shape = RoundedCornerShape(CARD_CORNER_DP.dp)
            val ink = if (card.selected) ROUTE_CHIP_SELECTED_INK else ROUTE_VALUE_COLOR
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MIN_TAP_TARGET_DP.dp)
                        .background(if (card.selected) ROUTE_CHIP_SELECTED_COLOR else ROUTE_CHIP_COLOR, shape)
                        .border(1.dp, if (card.selected) ROUTE_CHIP_SELECTED_COLOR else ROUTE_CONTROL_BORDER, shape)
                        .selectable(
                            selected = card.selected,
                            role = Role.RadioButton,
                            onClick = { onSelectAlternative(card.index) },
                        ).padding(horizontal = CHIP_PADDING_HORIZONTAL_DP.dp, vertical = CHIP_PADDING_VERTICAL_DP.dp),
                verticalArrangement = Arrangement.spacedBy(CARD_LABEL_GAP_DP.dp),
            ) {
                if (card.label.isNotEmpty()) {
                    BasicText(
                        text = card.label,
                        style =
                            TextStyle(
                                color = ink,
                                fontSize = STATUS_FONT_SP.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                }
                BasicText(
                    text = card.description,
                    style = TextStyle(color = ink, fontSize = STATUS_FONT_SP.sp),
                    modifier = Modifier.semantics { contentDescription = card.description },
                )
            }
        }
    }
}

private const val MODE_SEPARATOR = " · "
private const val ALTERNATIVE_DESCRIPTION_KEY = "route_planner_alternative_description"
private const val SELECTED_KEY = "route_alternative_selected"
private const val MIN_TAP_TARGET_DP = 48
private const val CHIP_CORNER_DP = 8
private const val CARD_CORNER_DP = 10
private const val CHIP_GAP_DP = 8
private const val CARD_LABEL_GAP_DP = 2
private const val CHIP_PADDING_HORIZONTAL_DP = 10
private const val CHIP_PADDING_VERTICAL_DP = 6
private const val STATUS_FONT_SP = 13
