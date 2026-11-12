package nl.r1a.grassmap.plugins.guidance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenColors
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenStatus
import nl.r1a.grassmap.plugin.ui.api.NavigationTurnArrow
import nl.r1a.grassmap.plugin.ui.api.NavigationTurnCard

private const val CARD_CORNER_DP = 18
private const val CARD_MAX_WIDTH_DP = 520
private const val MAIN_PADDING_HORIZONTAL_DP = 16
private const val MAIN_PADDING_VERTICAL_DP = 12
private const val MAIN_GAP_DP = 14
private const val STRIP_PADDING_HORIZONTAL_DP = 16
private const val STRIP_PADDING_VERTICAL_DP = 8
private const val STRIP_GAP_DP = 8
private const val ARROW_FONT_SP = 46
private const val TITLE_FONT_SP = 30
private const val STREET_FONT_SP = 19
private const val DETAIL_FONT_SP = 18
private const val STRIP_FONT_SP = 14
private const val STRIP_ARROW_FONT_SP = 18
private const val DIM_ALPHA = 0.55f

/**
 * The read-only turn card from round 5 (T-0320).
 *
 * It draws only the [card] the app derived from one published navigation state.
 * It reads no camera, location or navigation flow, so a gesture or camera frame
 * cannot recompute guidance. The card is the guidance panel's own chrome; the
 * shell positions it at the top and owns the rest of the navigation screen.
 *
 * Off route, paused and arrival replace the turn facts with the state's own
 * title and detail and keep the strip for the instruction that follows. A
 * missing GPS fix dims the last known turn.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun GuidancePanelContent(
    card: NavigationTurnCard,
    strings: Strings,
    modifier: Modifier = Modifier,
) {
    val (background, stripBackground) = cardColors(card.status)
    val stripInk =
        if (card.status == NavigationScreenStatus.Following || card.status == NavigationScreenStatus.Arrived) {
            Color(NavigationScreenColors.GREEN_INK)
        } else {
            Color.White
        }
    val content = Color.White
    val shape =
        if (card.strip != null || card.thenStreet != null) {
            RoundedCornerShape(topStart = CARD_CORNER_DP.dp, topEnd = CARD_CORNER_DP.dp)
        } else {
            RoundedCornerShape(CARD_CORNER_DP.dp)
        }
    Column(modifier = modifier.fillMaxWidth().widthIn(max = CARD_MAX_WIDTH_DP.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(background, shape)
                    .padding(horizontal = MAIN_PADDING_HORIZONTAL_DP.dp, vertical = MAIN_PADDING_VERTICAL_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(MAIN_GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dim = if (card.dimmed) DIM_ALPHA else 1f
            BasicText(
                text = arrowGlyph(card.arrow),
                modifier = Modifier.alpha(dim),
                style =
                    TextStyle(
                        color = content,
                        fontSize = ARROW_FONT_SP.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
            )
            Column(modifier = Modifier.alpha(dim)) {
                BasicText(
                    text = card.title ?: card.distance,
                    style =
                        TextStyle(
                            color = content,
                            fontSize = TITLE_FONT_SP.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                )
                BasicText(
                    text = card.detail ?: card.street,
                    style =
                        TextStyle(
                            color = content,
                            fontSize = if (card.title != null) DETAIL_FONT_SP.sp else STREET_FONT_SP.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                )
            }
        }
        val stripText = card.strip
        val thenStreet = card.thenStreet
        if (stripText != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            stripBackground,
                            RoundedCornerShape(bottomStart = CARD_CORNER_DP.dp, bottomEnd = CARD_CORNER_DP.dp),
                        ).padding(
                            horizontal = STRIP_PADDING_HORIZONTAL_DP.dp,
                            vertical = STRIP_PADDING_VERTICAL_DP.dp,
                        ),
                horizontalArrangement = Arrangement.spacedBy(STRIP_GAP_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = stripText,
                    style =
                        TextStyle(
                            color = stripInk,
                            fontSize = STRIP_FONT_SP.sp,
                        ),
                )
            }
        } else if (thenStreet != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            stripBackground,
                            RoundedCornerShape(bottomStart = CARD_CORNER_DP.dp, bottomEnd = CARD_CORNER_DP.dp),
                        ).padding(
                            horizontal = STRIP_PADDING_HORIZONTAL_DP.dp,
                            vertical = STRIP_PADDING_VERTICAL_DP.dp,
                        ),
                horizontalArrangement = Arrangement.spacedBy(STRIP_GAP_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = strings.resolve(GuidanceStrings.SCREEN_THEN),
                    style = TextStyle(color = stripInk, fontSize = STRIP_FONT_SP.sp),
                )
                BasicText(
                    text = arrowGlyph(card.thenArrow ?: NavigationTurnArrow.Straight),
                    style =
                        TextStyle(
                            color = stripInk,
                            fontSize = STRIP_ARROW_FONT_SP.sp,
                        ),
                )
                BasicText(
                    text = thenStreet,
                    style = TextStyle(color = content, fontSize = STRIP_FONT_SP.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

private fun cardColors(status: NavigationScreenStatus): Pair<Color, Color> =
    when (status) {
        NavigationScreenStatus.Following -> {
            Color(NavigationScreenColors.GREEN) to Color(NavigationScreenColors.GREEN_STRIP)
        }

        NavigationScreenStatus.OffRoute -> {
            Color(NavigationScreenColors.OFF_ROUTE) to Color(NavigationScreenColors.OFF_ROUTE_STRIP)
        }

        NavigationScreenStatus.Paused -> {
            Color(NavigationScreenColors.PAUSED) to Color(NavigationScreenColors.PAUSED_STRIP)
        }

        NavigationScreenStatus.Arrived -> {
            Color(NavigationScreenColors.GREEN) to Color(NavigationScreenColors.GREEN_STRIP)
        }
    }

/** The plain glyph a turn arrow draws; the card has no icon font. */
internal fun arrowGlyph(arrow: NavigationTurnArrow): String =
    when (arrow) {
        NavigationTurnArrow.Straight -> "\u2191"
        NavigationTurnArrow.SlightLeft -> "\u2196"
        NavigationTurnArrow.Left -> "\u21B0"
        NavigationTurnArrow.SharpLeft -> "\u21B6"
        NavigationTurnArrow.Uturn -> "\u21BA"
        NavigationTurnArrow.SlightRight -> "\u2197"
        NavigationTurnArrow.Right -> "\u21B1"
        NavigationTurnArrow.SharpRight -> "\u21B7"
        NavigationTurnArrow.Roundabout -> "\u21BB"
        NavigationTurnArrow.Arrive -> "\u2691"
        NavigationTurnArrow.Reroute -> "\u27F3"
        NavigationTurnArrow.Pause -> "\u2016"
    }

/**
 * Maps an engine maneuver to the card's arrow.
 *
 * The route model keeps the engine's own maneuver code (Valhalla's numeric
 * type, or null for the direct engine and the fixture routes). A numeric code
 * wins; without one the instruction text names the side. An unknown maneuver is
 * a straight-ahead arrow rather than a guess at a turn.
 */
fun turnArrow(
    maneuver: String?,
    text: String,
): NavigationTurnArrow {
    maneuver?.toIntOrNull()?.let { code -> VALHALLA_ARROWS[code]?.let { return it } }
    val lower = text.lowercase()
    return when {
        lower.contains("arrive") -> NavigationTurnArrow.Arrive
        lower.contains("u-turn") || lower.contains("uturn") -> NavigationTurnArrow.Uturn
        lower.contains("sharp left") -> NavigationTurnArrow.SharpLeft
        lower.contains("sharp right") -> NavigationTurnArrow.SharpRight
        lower.contains("slight left") || lower.contains("keep left") -> NavigationTurnArrow.SlightLeft
        lower.contains("slight right") || lower.contains("keep right") -> NavigationTurnArrow.SlightRight
        lower.contains("roundabout") -> NavigationTurnArrow.Roundabout
        lower.contains("left") -> NavigationTurnArrow.Left
        lower.contains("right") -> NavigationTurnArrow.Right
        else -> NavigationTurnArrow.Straight
    }
}

/** Valhalla maneuver codes, the same T-0310 mapping the follower uses. */
private val VALHALLA_ARROWS: Map<Int, NavigationTurnArrow> =
    mapOf(
        4 to NavigationTurnArrow.Arrive,
        5 to NavigationTurnArrow.Arrive,
        6 to NavigationTurnArrow.Arrive,
        9 to NavigationTurnArrow.SlightRight,
        10 to NavigationTurnArrow.Right,
        11 to NavigationTurnArrow.SharpRight,
        12 to NavigationTurnArrow.Uturn,
        13 to NavigationTurnArrow.Uturn,
        14 to NavigationTurnArrow.SharpLeft,
        15 to NavigationTurnArrow.Left,
        16 to NavigationTurnArrow.SlightLeft,
        18 to NavigationTurnArrow.SlightRight,
        19 to NavigationTurnArrow.SlightLeft,
        20 to NavigationTurnArrow.Right,
        21 to NavigationTurnArrow.Left,
        23 to NavigationTurnArrow.SlightRight,
        24 to NavigationTurnArrow.SlightLeft,
        26 to NavigationTurnArrow.Roundabout,
        27 to NavigationTurnArrow.Roundabout,
    )
