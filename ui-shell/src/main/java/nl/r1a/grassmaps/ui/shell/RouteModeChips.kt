package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
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
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.api.unit.UnitText
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.chipOutline
import nl.r1a.grassmaps.plugin.ui.api.choiceOutline

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
 * The chip reads [canonicalDurationSeconds], the same time the preview card
 * reads, so one route never shows two durations in the same view.
 */
fun routeModeChips(
    state: RoutePlanState,
    strings: Strings,
    time: RoutePreviewTime? = null,
): List<RouteModeChip> {
    val selected = state.selected ?: return emptyList()
    val profile = state.profile
    return listOf(
        RouteModeChip(
            profile = profile,
            label = profileLabel(profile, strings),
            time = routePlanAlternativeText(canonicalDurationSeconds(selected, time), strings),
        ),
    )
}

/**
 * One returned route as a card, in the engine's own order. [label] is the bold
 * line: the selected route keeps its selected label, and another route names
 * the time or climb it saves over the selected one. [ascent] is null when the
 * engine supplied no ascent, so the field is left out rather than invented.
 * [index] is kept so a card reuses the planner's selection command.
 */
data class RouteAlternativeCard(
    val index: Int,
    val selected: Boolean,
    val label: String,
    val time: String,
    val ascent: String?,
) {
    /** The value line: the duration, with ascent beside it only when measured. */
    val description: String
        get() = if (ascent.isNullOrEmpty()) time else time + VALUE_SEPARATOR + ascent
}

/**
 * The engine's options as benefit cards. The benefit is always relative to the
 * selected route: a faster alternative says how many minutes it saves, and a
 * slower alternative with less climb says how many metres of climb it saves.
 * An alternative that saves neither leaves the label empty, because a benefit
 * that is not in the data must not be shown.
 *
 * [ascentMeters] is keyed by option index. A missing index or a non-positive
 * value means the engine gave no ascent for that route; neither the ascent
 * field nor a climb benefit is then invented.
 */
fun routeAlternativeCards(
    state: RoutePlanState,
    strings: Strings,
    time: RoutePreviewTime? = null,
    ascentMeters: Map<Int, Double> = emptyMap(),
    units: UnitSystem = UnitSystem.Metric,
): List<RouteAlternativeCard> {
    val selected = state.selected ?: return emptyList()
    val selectedSeconds = canonicalDurationSeconds(selected, time)
    val selectedAscent = ascentMeters[state.selectedIndex]
    return state.options.mapIndexed { index, option ->
        val seconds = if (index == state.selectedIndex) selectedSeconds else option.durationSeconds
        val climb = ascentMeters[index]?.takeIf { it.isFinite() && it > 0.0 }
        RouteAlternativeCard(
            index = index,
            selected = index == state.selectedIndex,
            label =
                if (index == state.selectedIndex) {
                    strings.resolve(SELECTED_KEY)
                } else {
                    benefitLabel(selectedSeconds, selectedAscent, option.durationSeconds, climb, strings)
                },
            time = routePlanAlternativeText(seconds, strings),
            ascent = climb?.let { strings.resolve(ASCENT_KEY, listOf(UnitText.elevation(it, units, strings))) },
        )
    }
}

/**
 * The truthful saving one alternative has over the selected route. Whole
 * minutes are floored, so a 59-second saving never reads as "1 min faster".
 * Climb is only named when both routes have an engine-supplied ascent and the
 * alternative is at least one metre lower.
 */
private fun benefitLabel(
    selectedSeconds: Double,
    selectedAscent: Double?,
    optionSeconds: Double,
    optionAscent: Double?,
    strings: Strings,
): String {
    val savedMinutes = Math.floor((selectedSeconds - optionSeconds) / SECONDS_PER_MINUTE).toLong()
    if (savedMinutes >= 1L) {
        return strings.resolve(FASTER_KEY, listOf(savedMinutes.toString()))
    }
    if (selectedAscent != null && optionAscent != null) {
        val savedClimb = selectedAscent - optionAscent
        if (savedClimb >= 1.0) {
            return strings.resolve(
                LESS_CLIMB_KEY,
                listOf(
                    kotlin.math
                        .round(savedClimb)
                        .toLong()
                        .toString(),
                ),
            )
        }
    }
    return ""
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
                        .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                        .background(ROUTE_CHIP_COLOR, shape)
                        .chipOutline(LocalShellColors.current, shape)
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
                        .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                        .background(if (card.selected) ROUTE_CHIP_SELECTED_COLOR else ROUTE_CHIP_COLOR, shape)
                        .choiceOutline(LocalShellColors.current, shape, selection = if (card.selected) ROUTE_CHIP_SELECTED_COLOR else null)
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
private const val VALUE_SEPARATOR = " · "
private const val SECONDS_PER_MINUTE = 60.0
private const val SELECTED_KEY = "route_alternative_selected"
private const val FASTER_KEY = "route_alternative_faster"
private const val LESS_CLIMB_KEY = "route_alternative_less_climb"
private const val ASCENT_KEY = "route_alternative_ascent"
private const val CHIP_CORNER_DP = 8
private const val CARD_CORNER_DP = 10
private const val CHIP_GAP_DP = 8
private const val CARD_LABEL_GAP_DP = 2
private const val CHIP_PADDING_HORIZONTAL_DP = 10
private const val CHIP_PADDING_VERTICAL_DP = 6
private const val STATUS_FONT_SP = 13
