package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import nl.r1a.grassmap.plugin.api.provider.PlaceHit

/**
 * The search screen (T-0203): one field for everything, the result list, and
 * the map markers of the same result set.
 *
 * The screen owns no query path. Each keystroke leaves through
 * [onQueryChange] and comes back as [hits]; the list and the markers are built
 * once by [searchScreenContent] from that one list, so they cannot disagree.
 * Tapping a row asks for the place card through [onSelect] with the provider id.
 *
 * Markers draw under the field and use [SearchMarkerProjection], which is the
 * map's own Web-Mercator model. [camera] and [viewport] are the live map
 * values, and [anchorOffset] is the same position offset the map host was given,
 * so a puck that sits off-centre keeps its markers under it.
 *
 * [cardModifier] positions the field and the list (the app adds status-bar and
 * edge padding there); the markers always use the full map viewport.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun SearchScreenHost(
    hits: List<PlaceHit>,
    loading: Boolean,
    failed: Boolean,
    camera: Camera,
    viewport: IntSize,
    anchorOffset: ScreenOffset,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier,
    onlineSearchEnabled: Boolean = false,
    positionAllowed: Boolean = false,
    onEnableOnlineSearch: () -> Unit = {},
    onAllowPosition: (Boolean) -> Unit = {},
) {
    val labels =
        SearchScreenLabels(
            title = stringResource(R.string.search_title),
            hint = stringResource(R.string.search_hint),
            close = stringResource(R.string.search_close),
            noMatches = stringResource(R.string.search_no_matches),
            notReady = stringResource(R.string.search_not_ready),
            loading = stringResource(R.string.search_loading),
            onlineOffSendsPosition = stringResource(R.string.search_online_off_sends_position),
            onlineOffNoPosition = stringResource(R.string.search_online_off_no_position),
            onlineOnSendsPosition = stringResource(R.string.search_online_on_sends_position),
            onlineOnNoPosition = stringResource(R.string.search_online_on_no_position),
            onlineEnable = stringResource(R.string.search_online_enable),
            onlineBadge = stringResource(R.string.search_online_badge),
            positionAllow = stringResource(R.string.search_position_allow),
            positionStop = stringResource(R.string.search_position_stop),
        )
    val onlineNotice = searchOnlineNotice(labels, onlineSearchEnabled, positionAllowed)
    // The field keeps its own text. A keystroke travels to the session and back
    // asynchronously, so waiting for the round trip before drawing the character
    // would drop characters typed quickly. The session's query is only the
    // search text; the field is the user's.
    var typed by remember { mutableStateOf("") }
    val content = searchScreenContent(hits, typed, loading, failed, labels, onlineNotice)
    var expanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    Box(modifier = modifier.fillMaxSize()) {
        content.markers.forEach { marker ->
            SearchMarkerDot(
                marker = marker,
                camera = camera,
                viewport = viewport,
                anchorOffset = anchorOffset,
            )
        }
        if (!expanded) {
            BasicText(
                text = content.labels.title,
                style = TextStyle(color = TEXT_PRIMARY, fontSize = FIELD_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
                modifier =
                    cardModifier
                        .background(PANEL_BACKGROUND, RoundedCornerShape(PANEL_CORNER_DP.dp))
                        .clickable { expanded = true }
                        .padding(FIELD_PADDING_DP.dp),
            )
        } else {
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            Column(
                modifier =
                    cardModifier
                        .widthIn(max = PANEL_MAX_WIDTH_DP.dp)
                        .background(PANEL_BACKGROUND, RoundedCornerShape(PANEL_CORNER_DP.dp))
                        .padding(PANEL_PADDING_DP.dp),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = typed,
                        onValueChange = {
                            typed = it
                            onQueryChange(it)
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = TEXT_PRIMARY, fontSize = FIELD_FONT_SP.sp),
                        modifier =
                            Modifier
                                .weight(WEIGHT_ONE)
                                .focusRequester(focusRequester)
                                .background(FIELD_BACKGROUND, RoundedCornerShape(FIELD_CORNER_DP.dp))
                                .padding(FIELD_PADDING_DP.dp),
                    )
                    BasicText(
                        text = CLOSE_GLYPH,
                        style = TextStyle(color = TEXT_PRIMARY, fontSize = CLOSE_FONT_SP.sp),
                        modifier =
                            Modifier
                                .clickable {
                                    expanded = false
                                    typed = ""
                                    onClear()
                                }.semantics { contentDescription = content.labels.close }
                                .padding(CLOSE_PADDING_DP.dp),
                    )
                }
                if (typed.isBlank()) {
                    BasicText(text = content.labels.hint, style = secondaryStyle())
                }
                if (content.loading && content.rows.isEmpty()) {
                    BasicText(text = content.labels.loading, style = secondaryStyle())
                }
                content.online?.let { notice ->
                    BasicText(text = notice.text, style = secondaryStyle())
                    notice.action?.let { action ->
                        BasicText(
                            text = action,
                            style = TextStyle(color = TEXT_PRIMARY, fontSize = DETAIL_FONT_SP.sp),
                            modifier =
                                Modifier
                                    .clickable {
                                        when (notice.actionKind) {
                                            SearchOnlineAction.ENABLE -> onEnableOnlineSearch()
                                            SearchOnlineAction.ALLOW_POSITION -> onAllowPosition(true)
                                            SearchOnlineAction.STOP_POSITION -> onAllowPosition(false)
                                        }
                                    }.padding(ROW_PADDING_DP.dp),
                        )
                    }
                }
                content.rows.forEach { row ->
                    SearchResultRowContent(
                        row = row,
                        onlineBadge = content.labels.onlineBadge,
                        onSelect = { onSelect(row.id) },
                    )
                }
                content.message?.let { message -> BasicText(text = message, style = secondaryStyle()) }
            }
        }
    }
}

/**
 * One marker dot. Off-screen markers are skipped rather than clamped to the
 * edge, so the map never shows a result at a place it is not.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun SearchMarkerDot(
    marker: SearchMarker,
    camera: Camera,
    viewport: IntSize,
    anchorOffset: ScreenOffset,
) {
    val density = LocalDensity.current
    val offset =
        remember(marker, camera, viewport, anchorOffset, density) {
            val widthDp = with(density) { viewport.width.toDp().value }
            val heightDp = with(density) { viewport.height.toDp().value }
            SearchMarkerProjection.offset(
                position = marker.position,
                camera = camera,
                viewportWidthDp = widthDp,
                viewportHeightDp = heightDp,
                anchor = anchorOffset,
            )
        }
    if (!offset.visible) {
        return
    }
    Box(
        modifier =
            Modifier
                .offset {
                    IntOffset(
                        x = offset.xDp.dp.roundToPx(),
                        y = offset.yDp.dp.roundToPx(),
                    )
                }.size(MARKER_SIZE_DP.dp)
                .background(MARKER_COLOR, RoundedCornerShape(MARKER_CORNER_DP.dp)),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SearchResultRowContent(
    row: SearchResultRow,
    onlineBadge: String,
    onSelect: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(ROW_PADDING_DP.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = row.name,
                style = TextStyle(color = TEXT_PRIMARY, fontSize = ROW_FONT_SP.sp),
            )
            if (row.online) {
                BasicText(
                    text = ONLINE_BADGE_SEPARATOR + onlineBadge,
                    style = secondaryStyle(),
                )
            }
        }
        row.detail?.let { detail -> BasicText(text = detail, style = secondaryStyle()) }
    }
}

private fun secondaryStyle(): TextStyle = TextStyle(color = TEXT_SECONDARY, fontSize = DETAIL_FONT_SP.sp)

private const val CLOSE_GLYPH = "×"
private const val ONLINE_BADGE_SEPARATOR = " · "
private const val WEIGHT_ONE = 1f
private const val PANEL_CORNER_DP = 12
private const val PANEL_PADDING_DP = 12
private const val PANEL_MAX_WIDTH_DP = 420
private const val FIELD_PADDING_DP = 8
private const val FIELD_CORNER_DP = 6
private const val ROW_GAP_DP = 4
private const val ROW_PADDING_DP = 6
private const val CLOSE_PADDING_DP = 8
private const val MARKER_SIZE_DP = 12
private const val MARKER_CORNER_DP = 6
private const val TITLE_FONT_SP = 20
private const val FIELD_FONT_SP = 16
private const val ROW_FONT_SP = 15
private const val DETAIL_FONT_SP = 13
private const val CLOSE_FONT_SP = 20

private val PANEL_BACKGROUND = Color(0xF21B1B1B)
private val FIELD_BACKGROUND = Color(0xFF2B2B2B)
private val TEXT_PRIMARY = Color.White
private val TEXT_SECONDARY = Color(0xFFB8B8B8)
private val MARKER_COLOR = Color(0xFF4FC3F7)
