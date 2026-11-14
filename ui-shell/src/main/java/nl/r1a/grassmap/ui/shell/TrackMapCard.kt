package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.service.Strings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A tapped track as the map card reads it (T-0415): the name, the folder and
 * date line, the one-line statistics and the three actions of the round-6
 * track card (`t-map-selected`): Analysis, Color and Hide.
 *
 * The card is a shell model, not a stored record: the map screen fills it from
 * the sidecar summary it loaded after the tap, so the shell needs no track
 * dependency. [colours] is the card's choice, which is the track palette itself
 * (`TrackPalette.COLOURS`), so every value the card offers is a colour the map
 * already knows how to draw.
 */
data class TrackMapCard(
    val id: String,
    val name: String?,
    val colour: String,
    val listName: String?,
    val savedAtMillis: Long,
    val distanceMeters: Double,
    val ascentMeters: Double,
    val movingMillis: Long?,
    val hidden: Boolean = false,
    val colours: List<String> = emptyList(),
)

/** One colour swatch of the card's colour row. */
data class TrackColourChoice(
    val colour: String,
    val selected: Boolean,
    val description: String,
)

/** The card's text with every label resolved and every absent field dropped. */
data class TrackMapCardContent(
    val name: String,
    val colour: Long?,
    val meta: String?,
    val stats: String,
    val analysis: String,
    val colourAction: String,
    val visibilityAction: String,
    val hiddenNotice: String?,
    val colours: List<TrackColourChoice>,
)

/** The action a pressed card button asks the map screen to take. */
enum class TrackMapCardAction {
    ANALYSIS,
    VISIBILITY,
}

fun trackMapCardContent(
    card: TrackMapCard,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
): TrackMapCardContent {
    val parts = mutableListOf<String>()
    card.listName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { parts += it }
    if (card.savedAtMillis > 0L) {
        parts += dateText(card.savedAtMillis, locale)
    }
    return TrackMapCardContent(
        name = card.name?.trim()?.takeIf { it.isNotEmpty() } ?: strings.resolve(TRACK_ANALYSIS_UNTITLED_KEY),
        colour = favouriteColour(card.colour),
        meta = parts.takeIf { it.isNotEmpty() }?.joinToString(META_SEPARATOR),
        stats = statsLine(card, strings, locale),
        analysis = strings.resolve(TRACK_CARD_ANALYSIS_KEY),
        colourAction = strings.resolve(TRACK_CARD_COLOUR_KEY),
        visibilityAction = strings.resolve(if (card.hidden) TRACK_CARD_SHOW_KEY else TRACK_CARD_HIDE_KEY),
        hiddenNotice = if (card.hidden) strings.resolve(TRACK_CARD_HIDDEN_KEY) else null,
        colours =
            card.colours.mapIndexed { index, colour ->
                TrackColourChoice(
                    colour = colour,
                    selected = colour.equals(card.colour, ignoreCase = true),
                    description = strings.resolve(TRACK_CARD_COLOUR_CHOICE_KEY, listOf((index + 1).toString())),
                )
            },
    )
}

/** The design's one line: distance, ascent and moving time, each only when measured. */
private fun statsLine(
    card: TrackMapCard,
    strings: Strings,
    locale: Locale,
): String {
    val parts = mutableListOf<String>()
    if (card.distanceMeters > 0.0) {
        parts += distanceText(card.distanceMeters, strings, locale)
    }
    if (card.ascentMeters > 0.0) {
        parts +=
            strings.resolve(
                TRACK_CARD_ASCENT_KEY,
                listOf(strings.resolve(TRACK_ANALYSIS_METRES_KEY, listOf(card.ascentMeters.toLong().toString()))),
            )
    }
    card.movingMillis?.takeIf { it > 0L }?.let { millis ->
        parts += durationText(millis / MILLIS_PER_SECOND, strings)
    }
    return parts.joinToString(META_SEPARATOR)
}

private fun dateText(
    savedAtMillis: Long,
    locale: Locale,
): String =
    DATE_FORMAT.withLocale(locale).format(
        Instant.ofEpochMilli(savedAtMillis).atZone(ZoneId.systemDefault()),
    )

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/**
 * The card drawn at the bottom of the map. Its layout is the round-6
 * `t-map-selected` screen: the colour dot and name, the folder and date line,
 * the statistics line, then Analysis (filled) beside Color and Hide.
 *
 * Pressing Color reveals the look screen's six swatches (`t-looks`) in place;
 * pressing a swatch reports the colour and keeps the row open so the map can
 * draw the change under it. A hidden track's visibility action reads Show and
 * the card names the state, so the same control restores it.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun TrackMapCardHost(
    card: TrackMapCard,
    strings: Strings,
    onAction: (TrackMapCardAction) -> Unit,
    onColour: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = trackMapCardContent(card, strings)
    var coloursShown by remember(card.id) { mutableStateOf(false) }
    Column(
        modifier =
            modifier
                .padding(CARD_MARGIN_DP.dp)
                .widthIn(max = CARD_MAX_WIDTH_DP.dp)
                .fillMaxWidth()
                .background(ROUTE_PANEL_BACKGROUND, RoundedCornerShape(CARD_CORNER_DP.dp))
                .padding(CARD_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(CARD_GAP_DP.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DOT_GAP_DP.dp),
        ) {
            Box(
                Modifier
                    .size(DOT_SIZE_DP.dp)
                    .background(Color(content.colour ?: DEFAULT_DOT_COLOUR), CircleShape),
            )
            BasicText(
                text = content.name,
                style =
                    TextStyle(
                        color = ROUTE_VALUE_COLOR,
                        fontSize = NAME_FONT_SP.sp,
                        fontWeight = FontWeight.Bold,
                    ),
            )
        }
        content.meta?.let { meta ->
            BasicText(
                text = meta,
                style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = META_FONT_SP.sp),
                modifier = Modifier.padding(start = META_INDENT_DP.dp),
            )
        }
        if (content.stats.isNotEmpty()) {
            BasicText(
                text = content.stats,
                style =
                    TextStyle(
                        color = ROUTE_VALUE_COLOR,
                        fontSize = STATS_FONT_SP.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                modifier = Modifier.padding(start = META_INDENT_DP.dp),
            )
        }
        content.hiddenNotice?.let { notice ->
            BasicText(
                text = notice,
                style = TextStyle(color = ROUTE_CHANGED_COLOR, fontSize = META_FONT_SP.sp),
                modifier = Modifier.padding(start = META_INDENT_DP.dp),
            )
        }
        if (coloursShown && content.colours.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = SWATCH_ROW_GAP_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(SWATCH_GAP_DP.dp),
            ) {
                content.colours.forEach { choice ->
                    Box(
                        Modifier
                            .size(SWATCH_SIZE_DP.dp)
                            .background(Color(favouriteColour(choice.colour) ?: DEFAULT_DOT_COLOUR), CircleShape)
                            .then(
                                if (choice.selected) {
                                    Modifier.border(SWATCH_RING_DP.dp, ROUTE_VALUE_COLOR, CircleShape)
                                } else {
                                    Modifier
                                },
                            ).semantics { contentDescription = choice.description }
                            .clickable { onColour(choice.colour) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ACTION_ROW_GAP_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(ACTION_GAP_DP.dp),
        ) {
            ActionButton(
                label = content.analysis,
                filled = true,
                modifier = Modifier.weight(1f),
                onClick = { onAction(TrackMapCardAction.ANALYSIS) },
            )
            ActionButton(
                label = content.colourAction,
                filled = false,
                onClick = { coloursShown = !coloursShown },
            )
            ActionButton(
                label = content.visibilityAction,
                filled = false,
                onClick = { onAction(TrackMapCardAction.VISIBILITY) },
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ActionButton(
    label: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .background(
                    if (filled) ROUTE_ACTION_COLOR else ROUTE_CHIP_COLOR,
                    RoundedCornerShape(BUTTON_CORNER_DP.dp),
                ).clickable(onClick = onClick)
                .padding(horizontal = BUTTON_HORIZONTAL_DP.dp, vertical = BUTTON_VERTICAL_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style =
                TextStyle(
                    color = ROUTE_VALUE_COLOR,
                    fontSize = BUTTON_FONT_SP.sp,
                    fontWeight = FontWeight.Bold,
                ),
        )
    }
}

private const val TRACK_ANALYSIS_UNTITLED_KEY = "track_analysis_untitled"
private const val TRACK_ANALYSIS_METRES_KEY = "track_analysis_metres"
private const val TRACK_CARD_ANALYSIS_KEY = "track_card_analysis"
private const val TRACK_CARD_COLOUR_KEY = "track_card_colour"
private const val TRACK_CARD_HIDE_KEY = "track_card_hide"
private const val TRACK_CARD_SHOW_KEY = "track_card_show"
private const val TRACK_CARD_HIDDEN_KEY = "track_card_hidden"
private const val TRACK_CARD_ASCENT_KEY = "track_card_ascent"
private const val TRACK_CARD_COLOUR_CHOICE_KEY = "track_card_colour_choice"

private const val META_SEPARATOR = " · "
private const val MILLIS_PER_SECOND = 1_000.0
private const val DEFAULT_DOT_COLOUR = 0xFF2A78D6L

private const val CARD_CORNER_DP = 12
private const val CARD_PADDING_DP = 14
private const val CARD_MARGIN_DP = 12
private const val CARD_MAX_WIDTH_DP = 520
private const val CARD_GAP_DP = 2
private const val DOT_SIZE_DP = 11
private const val DOT_GAP_DP = 8
private const val META_INDENT_DP = 19
private const val NAME_FONT_SP = 19
private const val META_FONT_SP = 12
private const val STATS_FONT_SP = 15
private const val SWATCH_SIZE_DP = 30
private const val SWATCH_GAP_DP = 12
private const val SWATCH_RING_DP = 2
private const val SWATCH_ROW_GAP_DP = 8
private const val ACTION_ROW_GAP_DP = 8
private const val ACTION_GAP_DP = 8
private const val BUTTON_CORNER_DP = 22
private const val BUTTON_HORIZONTAL_DP = 16
private const val BUTTON_VERTICAL_DP = 10
private const val BUTTON_FONT_SP = 15
