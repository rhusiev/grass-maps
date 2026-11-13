package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.service.Strings

private const val TITLE_FONT_SP = 15
private const val DETAIL_FONT_SP = 13
private const val ACTION_FONT_SP = 14
private const val NOTICE_CORNER_DP = 12
private const val NOTICE_PADDING_HORIZONTAL_DP = 14
private const val NOTICE_PADDING_VERTICAL_DP = 10
private const val ACTION_PADDING_HORIZONTAL_DP = 10
private const val ACTION_PADDING_VERTICAL_DP = 4
private const val CONTENT_GAP_DP = 12

private const val ONE_KEY = "track_import_one"
private const val ONE_UNNAMED_KEY = "track_import_one_unnamed"
private const val MANY_KEY = "track_import_many"
private const val SKIPPED_ONE_KEY = "track_import_skipped_one"
private const val SKIPPED_MANY_KEY = "track_import_skipped_many"
private const val OPEN_KEY = "track_import_open"
private const val NOT_A_TRACK_KEY = "track_import_not_a_track"
private const val UNREADABLE_KEY = "track_import_unreadable"

/** Why a shared document did not become a track (T-0416). */
enum class TrackImportFailure {
    /** The bytes are not a document this app can read as a track. */
    NotATrack,

    /** The URI could not be opened or read at all. */
    Unreadable,
}

/**
 * What one share-sheet import produced, in the shell's vocabulary.
 *
 * `app` maps the importer's typed outcome onto this value, so the shell names
 * no `tracks` type and one notice shape covers success, a partial document and
 * a failure. [names] is the stored tracks in document order, [skipped] the
 * document parts the format dropped, and [failure] is set only when nothing was
 * stored.
 */
data class TrackImportOutcome(
    val names: List<String?> = emptyList(),
    val skipped: Int = 0,
    val failure: TrackImportFailure? = null,
) {
    init {
        require(skipped >= 0) { "the skipped count cannot be negative" }
    }

    /** How many tracks the import stored. */
    val trackCount: Int get() = names.size
}

/** The notice's resolved text, ready to draw. */
data class TrackImportNotice(
    val title: String,
    val detail: String?,
    val action: String?,
    val failed: Boolean = false,
)

/**
 * The notice another app's share produced (T-0416), from the round-6
 * `t-imported` design: one dark line at the bottom of the map that names what
 * arrived and offers to open it. A failed import says what could not be read
 * and offers no action, because there is nothing to open.
 */
fun trackImportNotice(
    outcome: TrackImportOutcome,
    strings: Strings,
): TrackImportNotice {
    outcome.failure?.let { failure ->
        return TrackImportNotice(
            title = strings.resolve(failureKey(failure)),
            detail = null,
            action = null,
            failed = true,
        )
    }
    return TrackImportNotice(
        title = titleOf(outcome, strings),
        detail = skippedDetail(outcome.skipped, strings),
        action = strings.resolve(OPEN_KEY).takeIf { outcome.trackCount > 0 },
    )
}

private fun titleOf(
    outcome: TrackImportOutcome,
    strings: Strings,
): String {
    if (outcome.trackCount == 1) {
        val name = outcome.names.single()?.takeIf { it.isNotBlank() } ?: return strings.resolve(ONE_UNNAMED_KEY)
        return strings.resolve(ONE_KEY, listOf(name))
    }
    return strings.resolve(MANY_KEY, listOf(outcome.trackCount.toString()))
}

private fun skippedDetail(
    skipped: Int,
    strings: Strings,
): String? =
    when (skipped) {
        0 -> null
        1 -> strings.resolve(SKIPPED_ONE_KEY)
        else -> strings.resolve(SKIPPED_MANY_KEY, listOf(skipped.toString()))
    }

private fun failureKey(failure: TrackImportFailure): String =
    when (failure) {
        TrackImportFailure.NotATrack -> NOT_A_TRACK_KEY
        TrackImportFailure.Unreadable -> UNREADABLE_KEY
    }

/**
 * The notice row. The whole row is one announcement and [onOpen] is the only
 * control, so a screen reader reaches the stored track from the feedback.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun TrackImportNoticeBar(
    notice: TrackImportNotice,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(ROUTE_PANEL_BACKGROUND, RoundedCornerShape(NOTICE_CORNER_DP.dp))
                .padding(
                    horizontal = NOTICE_PADDING_HORIZONTAL_DP.dp,
                    vertical = NOTICE_PADDING_VERTICAL_DP.dp,
                ),
        horizontalArrangement = Arrangement.spacedBy(CONTENT_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = notice.title,
                style =
                    TextStyle(
                        color = if (notice.failed) ROUTE_ERROR_COLOR else ROUTE_VALUE_COLOR,
                        fontSize = TITLE_FONT_SP.sp,
                        fontWeight = FontWeight.Bold,
                    ),
            )
            notice.detail?.let { detail ->
                BasicText(
                    text = detail,
                    style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = DETAIL_FONT_SP.sp),
                )
            }
        }
        notice.action?.let { action ->
            BasicText(
                text = action,
                style =
                    TextStyle(
                        color = ROUTE_ACTION_COLOR,
                        fontSize = ACTION_FONT_SP.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(NOTICE_CORNER_DP.dp))
                        .clickable(onClick = onOpen)
                        .semantics { contentDescription = action }
                        .padding(
                            horizontal = ACTION_PADDING_HORIZONTAL_DP.dp,
                            vertical = ACTION_PADDING_VERTICAL_DP.dp,
                        ),
            )
        }
    }
}
