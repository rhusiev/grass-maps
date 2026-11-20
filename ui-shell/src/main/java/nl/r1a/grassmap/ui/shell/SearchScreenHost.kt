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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.ui.api.LocalShellColors
import nl.r1a.grassmap.plugin.ui.api.ShellColors

/**
 * The search screen (T-0203): one field for everything, the result list, and
 * the map markers of the same result set.
 *
 * The screen owns no query path. Each keystroke leaves through
 * [onQueryChange] and comes back as [hits]; the list and the markers are built
 * once by [searchScreenContent] from that one list, so they cannot disagree.
 * Tapping a row asks for the place card through [onSelect] with the provider id.
 *
 * A keystroke writes nothing to the search history. The field's Search action
 * calls [onSubmit] with the field's text, and a row tap calls [onPick] with the
 * row's name; those are the two events the shell may store. [onSelect] and
 * [onPick] both fire on a row tap, one for the place and one for the history.
 *
 * Markers draw under the field and use [SearchMarkerProjection], which is the
 * map's own Web-Mercator model. [camera] and [viewport] are the live map
 * values, and [anchorOffset] is the same position offset the map host was given,
 * so a puck that sits off-centre keeps its markers under it.
 *
 * [cardModifier] positions the field and the list (the app adds status-bar and
 * edge padding there); the markers always use the full map viewport.
 * [onCollapsedCardSize] receives the size of the closed field, so a screen can
 * place its own elements below it at any font scale.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun SearchScreenHost(
    hits: List<PlaceHit>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    loading: Boolean,
    failed: Boolean,
    camera: Camera,
    viewport: IntSize,
    anchorOffset: ScreenOffset,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier,
    onlineSearchEnabled: Boolean = false,
    positionAllowed: Boolean = false,
    onEnableOnlineSearch: () -> Unit = {},
    onAllowPosition: (Boolean) -> Unit = {},
    onCollapsedCardSize: (IntSize) -> Unit = {},
) {
    val colors = LocalShellColors.current
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
                style = TextStyle(color = colors.ink, fontSize = FIELD_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
                modifier =
                    cardModifier
                        .onSizeChanged(onCollapsedCardSize)
                        .background(colors.glass, RoundedCornerShape(PANEL_CORNER_DP.dp))
                        .clickable(role = Role.Button) { onExpandedChange(true) }
                        .padding(FIELD_PADDING_DP.dp),
            )
        } else {
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            Column(
                modifier =
                    cardModifier
                        .widthIn(max = PANEL_MAX_WIDTH_DP.dp)
                        .background(colors.glass, RoundedCornerShape(PANEL_CORNER_DP.dp))
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
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSubmit(typed) }),
                        textStyle = TextStyle(color = colors.ink, fontSize = FIELD_FONT_SP.sp),
                        modifier =
                            Modifier
                                .weight(WEIGHT_ONE)
                                .focusRequester(focusRequester)
                                .background(colors.soft, RoundedCornerShape(FIELD_CORNER_DP.dp))
                                .padding(FIELD_PADDING_DP.dp)
                                .semantics { contentDescription = content.labels.hint },
                    )
                    BasicText(
                        text = CLOSE_GLYPH,
                        style = TextStyle(color = colors.ink, fontSize = CLOSE_FONT_SP.sp),
                        modifier =
                            Modifier
                                .clickable(role = Role.Button) {
                                    onExpandedChange(false)
                                    typed = ""
                                    onClear()
                                }.semantics { contentDescription = content.labels.close }
                                .padding(CLOSE_PADDING_DP.dp),
                    )
                }
                if (typed.isBlank()) {
                    // The field already carries this hint as its label; the
                    // drawn copy must not be a second announcement.
                    BasicText(
                        text = content.labels.hint,
                        style = secondaryStyle(colors),
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                if (content.loading && content.rows.isEmpty()) {
                    BasicText(text = content.labels.loading, style = secondaryStyle(colors))
                }
                content.online?.let { notice ->
                    BasicText(text = notice.text, style = secondaryStyle(colors))
                    notice.action?.let { action ->
                        BasicText(
                            text = action,
                            style = TextStyle(color = colors.ink, fontSize = DETAIL_FONT_SP.sp),
                            modifier =
                                Modifier
                                    .clickable(role = Role.Button) {
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
                        onSelect = {
                            onSelect(row.id)
                            onPick(row.name)
                        },
                    )
                }
                content.message?.let { message -> BasicText(text = message, style = secondaryStyle(colors)) }
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
    val colors = LocalShellColors.current
    Box(
        modifier =
            Modifier
                .offset {
                    IntOffset(
                        x = offset.xDp.dp.roundToPx(),
                        y = offset.yDp.dp.roundToPx(),
                    )
                }.size(MARKER_SIZE_DP.dp)
                .background(colors.categories.pin, RoundedCornerShape(MARKER_CORNER_DP.dp)),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SearchResultRowContent(
    row: SearchResultRow,
    onlineBadge: String,
    onSelect: () -> Unit,
) {
    val colors = LocalShellColors.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onSelect)
                .padding(ROW_PADDING_DP.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = row.name,
                style = TextStyle(color = colors.ink, fontSize = ROW_FONT_SP.sp),
            )
            if (row.online) {
                BasicText(
                    text = ONLINE_BADGE_SEPARATOR + onlineBadge,
                    style = secondaryStyle(colors),
                )
            }
        }
        row.detail?.let { detail -> BasicText(text = detail, style = secondaryStyle(colors)) }
    }
}

private fun secondaryStyle(colors: ShellColors): TextStyle = TextStyle(color = colors.muted, fontSize = DETAIL_FONT_SP.sp)

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
