package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
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
import nl.r1a.grassmaps.plugin.api.model.Camera
import nl.r1a.grassmaps.plugin.api.model.ScreenOffset
import nl.r1a.grassmaps.plugin.api.provider.PlaceHit
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellIcon
import nl.r1a.grassmaps.plugin.ui.api.ShellIcons
import kotlin.math.roundToLong

/** The expanded card opens in the bottom thumb area, just above the keyboard. */
private val SEARCH_EXPANDED_ANCHOR: Alignment = Alignment.BottomCenter

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
 * [cardModifier] adds edge padding to the field and the list; the app gives the
 * open list a top anchor and the closed field bottom-bar padding.
 * [collapsedAnchor] places only the closed field in this Box, so the open list
 * keeps its readable position above the keyboard. A host with
 * [showCollapsedField] false draws no closed field at all; the app's single
 * bottom bar supplies that entry point, and only the markers stay over the map.
 * The markers always use the full map viewport.
 * [onCollapsedCardSize] receives the size of the closed field, so a screen can
 * place its own elements below it at any font scale. When no offline index is
 * mounted, the line under the field offers [onOpenDownloads] as the next step;
 * the screen never turns downloads or online search on by itself.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun SearchScreenHost(
    hits: List<PlaceHit>,
    expanded: Boolean,
    showCollapsedField: Boolean = true,
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
    onShowOnMap: () -> Unit = {},
    modifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier,
    collapsedAnchor: Alignment = Alignment.TopStart,
    onlineSearchEnabled: Boolean = false,
    positionAllowed: Boolean = false,
    onEnableOnlineSearch: () -> Unit = {},
    onDisableOnlineSearch: () -> Unit = {},
    onAllowPosition: (Boolean) -> Unit = {},
    onlineStopped: Boolean = false,
    onCollapsedCardSize: (IntSize) -> Unit = {},
    onOpenDownloads: () -> Unit = {},
) {
    val colors = LocalShellColors.current
    val labels =
        SearchScreenLabels(
            title = stringResource(R.string.search_title),
            hint = stringResource(R.string.search_hint),
            close = stringResource(R.string.search_close),
            noMatches = stringResource(R.string.search_no_matches),
            notReady = stringResource(R.string.search_not_ready),
            notReadyAction = stringResource(R.string.search_not_ready_action),
            loading = stringResource(R.string.search_loading),
            onlineOffSendsPosition = stringResource(R.string.search_online_off_sends_position),
            onlineOffNoPosition = stringResource(R.string.search_online_off_no_position),
            onlineOnSendsPosition = stringResource(R.string.search_online_on_sends_position),
            onlineOnNoPosition = stringResource(R.string.search_online_on_no_position),
            onlineEnable = stringResource(R.string.search_online_enable),
            onlineDisable = stringResource(R.string.search_online_disable),
            onlineBadge = stringResource(R.string.search_online_badge),
            onlineStopped = stringResource(R.string.search_online_stopped),
            onlineStoppedWithOnline = stringResource(R.string.search_online_stopped_with_online),
            onlineStoppedNoResults = stringResource(R.string.search_online_stopped_no_results),
            positionAllow = stringResource(R.string.search_position_allow),
            positionStop = stringResource(R.string.search_position_stop),
            onlineChipOff = stringResource(R.string.search_online_chip_off),
            onlineChipOn = stringResource(R.string.search_online_chip_on),
        )
    val onlineNotice = searchOnlineNotice(labels, onlineSearchEnabled, positionAllowed)
    // The field keeps its own text. A keystroke travels to the session and back
    // asynchronously, so waiting for the round trip before drawing the character
    // would drop characters typed quickly. The session's query is only the
    // search text; the field is the user's.
    var typed by remember { mutableStateOf("") }
    val content =
        searchScreenContent(
            hits = hits,
            query = typed,
            loading = loading,
            failed = failed,
            labels = labels,
            online = onlineNotice,
            onlineStopped = onlineStopped,
            origin = camera.center,
        )
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
            if (showCollapsedField) {
                BasicText(
                    text = content.labels.title,
                    style = TextStyle(color = colors.ink, fontSize = FIELD_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
                    modifier =
                        Modifier
                            .align(collapsedAnchor)
                            .then(cardModifier)
                            .onSizeChanged(onCollapsedCardSize)
                            .background(colors.glass, RoundedCornerShape(PANEL_CORNER_DP.dp))
                            .clickable(role = Role.Button) { onExpandedChange(true) }
                            .padding(FIELD_PADDING_DP.dp),
                )
            }
        } else {
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            // The wrapper reserves the keyboard's space; the card aligns inside
            // it, so the query stays just above the keyboard while markers keep
            // using the full map viewport in the outer Box.
            Box(modifier = Modifier.fillMaxSize().imePadding()) {
                Column(
                    modifier =
                        cardModifier
                            .align(SEARCH_EXPANDED_ANCHOR)
                            .widthIn(max = PANEL_MAX_WIDTH_DP.dp)
                            .background(colors.surface, RoundedCornerShape(PANEL_CORNER_DP.dp))
                            .border(1.dp, colors.controlBorder, RoundedCornerShape(PANEL_CORNER_DP.dp))
                            .heightIn(max = PANEL_MAX_HEIGHT_DP.dp)
                            .padding(PANEL_PADDING_DP.dp),
                    verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
                ) {
                    // Only this part scrolls. The chips and the query are a
                    // pinned footer, so a long result list never pushes them
                    // out of the thumb area.
                    Column(
                        modifier =
                            Modifier
                                .weight(WEIGHT_ONE, fill = false)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
                    ) {
                        if (content.count > 0) {
                            val count = searchResultCount(content.rows)
                            BasicText(
                                text =
                                    pluralStringResource(
                                        searchCountPlural(count.kind),
                                        count.count,
                                        count.count.toString(),
                                    ),
                                style = secondaryStyle(colors),
                            )
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
                        if (content.loading && content.rows.isEmpty()) {
                            BasicText(text = content.labels.loading, style = secondaryStyle(colors))
                        }
                        content.message?.let { message -> BasicText(text = message, style = secondaryStyle(colors)) }
                        content.online?.let { notice ->
                            BasicText(text = notice.text, style = secondaryStyle(colors))
                            notice.action?.let { action ->
                                NoticeActionButton(action) {
                                    when (notice.actionKind) {
                                        SearchOnlineAction.ENABLE -> onEnableOnlineSearch()
                                        SearchOnlineAction.ALLOW_POSITION -> onAllowPosition(true)
                                        SearchOnlineAction.STOP_POSITION -> onAllowPosition(false)
                                    }
                                }
                            }
                            notice.offAction?.let { action -> NoticeActionButton(action, onDisableOnlineSearch) }
                        }
                        content.notReady?.let { notice ->
                            BasicText(text = notice.explanation, style = secondaryStyle(colors))
                            NoticeActionButton(notice.action) {
                                runNotReadyAction(notice.actionKind, onOpenDownloads)
                            }
                        }
                    }
                    if (content.count > 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP_DP.dp)) {
                            SearchChip(
                                text = stringResource(R.string.search_chip_show_on_map, content.count),
                                onClick = onShowOnMap,
                            )
                            SearchChip(
                                text = stringResource(R.string.search_chip_open_now),
                                enabled = content.rows.any { it.openState != null },
                                description = stringResource(R.string.search_chip_open_now_unavailable),
                            )
                            SearchChip(
                                text = searchOnlineChipText(labels, onlineSearchEnabled),
                                description = stringResource(R.string.search_online_chip_description),
                            )
                        }
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
                                    .border(1.dp, colors.controlBorder, RoundedCornerShape(FIELD_CORNER_DP.dp))
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
                }
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
    // The marker draws the row's derived glyph, so a kind cannot read as one
    // category in the list and another on the map.
    ShellIcon(
        icon = marker.iconKey.icon(),
        tint = colors.categories.pin,
        modifier =
            Modifier
                .offset {
                    IntOffset(
                        x = offset.xDp.dp.roundToPx(),
                        y = offset.yDp.dp.roundToPx(),
                    )
                }.size(MARKER_SIZE_DP.dp),
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
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onSelect)
                .padding(ROW_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(CATEGORY_ICON_DP.dp)
                    .background(
                        colors.categories.pin.copy(alpha = CATEGORY_ICON_TINT),
                        RoundedCornerShape(CATEGORY_ICON_CORNER_DP.dp),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            ShellIcon(
                icon = row.iconKey.icon(),
                tint = colors.categories.pin,
                modifier = Modifier.size(CATEGORY_ICON_GLYPH_DP.dp),
            )
        }
        Column(
            modifier =
                Modifier
                    .weight(WEIGHT_ONE)
                    .padding(start = ROW_PADDING_DP.dp),
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
            val metadata = searchResultMetadata(row)
            if (metadata != null) {
                BasicText(text = metadata, style = secondaryStyle(colors))
            } else {
                row.detail?.let { detail -> BasicText(text = detail, style = secondaryStyle(colors)) }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            row.distanceMeters?.let { meters ->
                BasicText(text = searchDistanceText(meters), style = secondaryStyle(colors))
            }
            row.openState?.let { state ->
                BasicText(text = state.label, style = secondaryStyle(colors))
            }
        }
    }
}

/** The canon's distance precision, from the same unit rules the map sheets use. */
@Composable
private fun searchDistanceText(meters: Double): String =
    if (meters < METRES_PER_KILOMETRE) {
        stringResource(R.string.search_result_distance_metres, meters.roundToLong())
    } else {
        stringResource(R.string.search_result_distance_kilometres, meters / METRES_PER_KILOMETRE)
    }

/**
 * One privacy or region action, drawn as a filled, bordered button with a
 * 48 dp target. The copy comes straight from the caller, so the data-transparency
 * line is never rewritten to make it fit the chrome.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun NoticeActionButton(
    text: String,
    onClick: () -> Unit,
) {
    val colors = LocalShellColors.current
    BasicText(
        text = text,
        style = TextStyle(color = colors.ink, fontSize = DETAIL_FONT_SP.sp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MIN_TAP_TARGET_DP.dp)
                .background(colors.soft, RoundedCornerShape(ACTION_CORNER_DP.dp))
                .border(1.dp, colors.controlBorder, RoundedCornerShape(ACTION_CORNER_DP.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = ACTION_PADDING_DP.dp)
                .wrapContentHeight(Alignment.CenterVertically),
    )
}

/**
 * One canon filter chip. A chip with no [onClick] is a quiet state indicator:
 * it stays visible but cannot be pressed, so no dead control is offered when a
 * result set carries no data for that filter.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun SearchChip(
    text: String,
    enabled: Boolean = true,
    description: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalShellColors.current
    val shape = RoundedCornerShape(ACTION_CORNER_DP.dp)
    val chrome =
        Modifier
            .heightIn(min = CHIP_HEIGHT_DP.dp)
            .background(colors.soft, shape)
            .border(1.dp, colors.controlBorder, shape)
            .padding(horizontal = ACTION_PADDING_DP.dp)
            .wrapContentHeight(Alignment.CenterVertically)
    val pressable =
        if (onClick == null) {
            chrome
        } else {
            chrome.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        }
    BasicText(
        text = text,
        style = TextStyle(color = if (enabled) colors.ink else colors.muted, fontSize = DETAIL_FONT_SP.sp),
        modifier =
            if (description == null) {
                pressable
            } else {
                pressable.semantics { contentDescription = description }
            },
    )
}

/**
 * The plural resource for the count header. A shared kind the shell can name
 * gets its own noun; every other set keeps the generic result word.
 */
private fun searchCountPlural(kind: String?): Int =
    when (searchKindCountKey(kind)) {
        "search_count_pharmacy" -> R.plurals.search_count_pharmacy
        "search_count_cafe" -> R.plurals.search_count_cafe
        "search_count_supermarket" -> R.plurals.search_count_supermarket
        "search_count_drinking_water" -> R.plurals.search_count_drinking_water
        "search_count_toilets" -> R.plurals.search_count_toilets
        "search_count_shelter" -> R.plurals.search_count_shelter
        "search_count_viewpoint" -> R.plurals.search_count_viewpoint
        "search_count_campsite" -> R.plurals.search_count_campsite
        "search_count_picnic_site" -> R.plurals.search_count_picnic_site
        else -> R.plurals.search_result_count
    }

/** The drawn shell glyph for a derived category, with the pin as the fallback. */
private fun SearchCategoryIconKey.icon(): ImageVector =
    when (this) {
        SearchCategoryIconKey.Pin -> ShellIcons.Pin
        SearchCategoryIconKey.Water -> ShellIcons.Drop
    }

private fun secondaryStyle(colors: ShellColors): TextStyle = TextStyle(color = colors.muted, fontSize = DETAIL_FONT_SP.sp)

private const val CLOSE_GLYPH = "×"
private const val ONLINE_BADGE_SEPARATOR = " · "
private const val WEIGHT_ONE = 1f

// The canon glass radius, from round9/_build/common.py's .glass rule.
private const val PANEL_CORNER_DP = 16
private const val PANEL_PADDING_DP = 12
private const val PANEL_MAX_WIDTH_DP = 420
private const val PANEL_MAX_HEIGHT_DP = 560
private const val FIELD_PADDING_DP = 8
private const val FIELD_CORNER_DP = 6
private const val ROW_GAP_DP = 4
private const val ROW_PADDING_DP = 6

// Shared by every notice button, including the no-index action.
private const val MIN_TAP_TARGET_DP = 48
private const val CHIP_HEIGHT_DP = 48
private const val CHIP_GAP_DP = 6
private const val ACTION_CORNER_DP = 10
private const val ACTION_PADDING_DP = 12
private const val CATEGORY_ICON_DP = 28
private const val CATEGORY_ICON_GLYPH_DP = 18
private const val CATEGORY_ICON_CORNER_DP = 8
private const val CATEGORY_ICON_TINT = 0.14f
private const val METRES_PER_KILOMETRE = 1_000.0
private const val CLOSE_PADDING_DP = 8
private const val MARKER_SIZE_DP = 12
private const val TITLE_FONT_SP = 20
private const val FIELD_FONT_SP = 16
private const val ROW_FONT_SP = 15
private const val DETAIL_FONT_SP = 13
private const val CLOSE_FONT_SP = 20
