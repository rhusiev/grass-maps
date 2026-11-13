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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.service.Strings
import java.util.Locale

private const val ENTRY_TITLE_FONT_SP = 14
private const val ENTRY_DETAIL_FONT_SP = 12
private const val SHEET_TITLE_FONT_SP = 16
private const val ROW_KIND_FONT_SP = 15
private const val ROW_DETAIL_FONT_SP = 12
private const val ROW_DISTANCE_FONT_SP = 15
private const val ENTRY_CORNER_DP = 14
private const val ENTRY_BORDER_DP = 1
private const val ENTRY_PADDING_HORIZONTAL_DP = 10
private const val ENTRY_PADDING_VERTICAL_DP = 7
private const val ENTRY_ICON_SIZE_DP = 32
private const val ROW_ICON_SIZE_DP = 38
private const val GAP_DP = 10
private const val SECTION_GAP_DP = 12
private const val TIGHT_GAP_DP = 2
private const val CHEVRON_GLYPH = "\u203A"
private const val SUMMARY_SEPARATOR = ", "

private const val COUNT_ONE_KEY = "route_warnings_count_one"
private const val COUNT_MANY_KEY = "route_warnings_count_many"
private const val SHEET_TITLE_KEY = "route_warnings_title"
private const val SCOPE_KEY = "route_warnings_scope"
private const val BACK_KEY = "route_warnings_back"
private const val KIND_FORD_KEY = "route_warnings_kind_ford"
private const val KIND_STEPS_KEY = "route_warnings_kind_steps"
private const val KIND_BARRIER_KEY = "route_warnings_kind_barrier"
private const val KIND_RAILWAY_CROSSING_KEY = "route_warnings_kind_railway_crossing"
private const val KIND_PRIVATE_WAY_KEY = "route_warnings_kind_private_way"
private const val DETAIL_PRIVATE_KEY = "route_warnings_detail_private"
private const val DETAIL_PUSH_BIKE_KEY = "route_warnings_detail_push_bike"

/**
 * The warning kinds the route panel lists (T-0409).
 *
 * The scanner publishes its own `RouteWarningKind` in the route-warnings
 * plugin, which `ui-shell` does not depend on. `app` maps one to one onto this
 * vocabulary, so the panel names the five kinds without reaching into a
 * plugin.
 */
enum class RouteWarningGlyph {
    Ford,
    Steps,
    Barrier,
    RailwayCrossing,
    PrivateWay,
}

/**
 * One warning as the panel lists it: which kind, and how far along the route
 * the rider meets it. The rows arrive in the scanner's distance order and the
 * panel keeps that order.
 */
data class RouteWarningRow(
    val glyph: RouteWarningGlyph,
    val distanceAlongRouteMeters: Double,
) {
    init {
        require(distanceAlongRouteMeters.isFinite() && distanceAlongRouteMeters >= 0.0) {
            "the distance along the route must be finite and not negative"
        }
    }
}

/** The collapsed row's resolved text. */
internal data class RouteWarningsEntryContent(
    val title: String,
    val summary: String,
)

/** The list's resolved text and rows. */
internal data class RouteWarningsSheetContent(
    val title: String,
    val scope: String,
    val rows: List<RouteWarningRowContent>,
    val back: String,
)

/** One warning row: the kind, its detail and the distance in the locale's units. */
internal data class RouteWarningRowContent(
    val glyph: RouteWarningGlyph,
    val kind: String,
    val detail: String?,
    val distance: String,
)

/**
 * The amber "N warnings on this route" row from the round-6 design (T-0409).
 * It sits above the route preferences entry and opens the list. A route with
 * no warnings has no row at all.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun RouteWarningsEntry(
    content: RouteWarningsEntryContent,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(ROUTE_WARNING_BACKGROUND, RoundedCornerShape(ENTRY_CORNER_DP.dp))
                .border(
                    ENTRY_BORDER_DP.dp,
                    ROUTE_WARNING_BORDER,
                    RoundedCornerShape(ENTRY_CORNER_DP.dp),
                ).clickable(onClick = onClick)
                .padding(
                    horizontal = ENTRY_PADDING_HORIZONTAL_DP.dp,
                    vertical = ENTRY_PADDING_VERTICAL_DP.dp,
                ),
        horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WarningGlyphCircle(ROUTE_WARNING_SYMBOL, ENTRY_ICON_SIZE_DP)
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = content.title,
                style =
                    TextStyle(
                        color = ROUTE_WARNING_TITLE_COLOR,
                        fontSize = ENTRY_TITLE_FONT_SP.sp,
                        fontWeight = FontWeight.Bold,
                    ),
            )
            BasicText(
                text = content.summary,
                style = TextStyle(color = ROUTE_WARNING_SUMMARY_COLOR, fontSize = ENTRY_DETAIL_FONT_SP.sp),
            )
        }
        BasicText(
            text = CHEVRON_GLYPH,
            style = TextStyle(color = ROUTE_WARNING_TITLE_COLOR, fontSize = SHEET_TITLE_FONT_SP.sp),
        )
    }
}

/**
 * The "On this route" list. One row per warning in the scanner's order, each
 * with its pictogram, kind, detail and distance. Back closes the list; the
 * panel's own Start stays above it, so the list adds no second Start.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun RouteWarningsContent(
    content: RouteWarningsSheetContent,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = TIGHT_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
    ) {
        Column {
            BasicText(
                text = content.title,
                style =
                    TextStyle(
                        color = ROUTE_VALUE_COLOR,
                        fontSize = SHEET_TITLE_FONT_SP.sp,
                        fontWeight = FontWeight.Bold,
                    ),
            )
            BasicText(
                text = content.scope,
                style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = ENTRY_DETAIL_FONT_SP.sp),
            )
        }
        for (row in content.rows) {
            WarningListRow(row)
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ActionChip(content.back, onBack)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun WarningListRow(row: RouteWarningRowContent) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WarningGlyphCircle(routeWarningGlyphText(row.glyph), ROW_ICON_SIZE_DP)
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = row.kind,
                style =
                    TextStyle(
                        color = ROUTE_VALUE_COLOR,
                        fontSize = ROW_KIND_FONT_SP.sp,
                        fontWeight = FontWeight.Bold,
                    ),
            )
            row.detail?.let { detail ->
                BasicText(
                    text = detail,
                    style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = ROW_DETAIL_FONT_SP.sp),
                )
            }
        }
        BasicText(
            text = row.distance,
            style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = ROW_DISTANCE_FONT_SP.sp),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun WarningGlyphCircle(
    glyph: String,
    sizeDp: Int,
) {
    Box(
        modifier =
            Modifier
                .size(sizeDp.dp)
                .background(ROUTE_WARNING_ICON_BACKGROUND, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = glyph,
            style = TextStyle(color = ROUTE_WARNING_ICON_COLOR, fontSize = ENTRY_TITLE_FONT_SP.sp),
        )
    }
}

/**
 * The collapsed row for [warnings], or null when there is nothing to warn
 * about. The count is never zero here, so a caller needs one check.
 */
internal fun routeWarningsEntryContent(
    warnings: List<RouteWarningRow>,
    strings: Strings,
): RouteWarningsEntryContent? {
    if (warnings.isEmpty()) {
        return null
    }
    val count = warnings.size
    val title =
        if (count == 1) {
            strings.resolve(COUNT_ONE_KEY)
        } else {
            strings.resolve(COUNT_MANY_KEY, listOf(count.toString()))
        }
    return RouteWarningsEntryContent(title = title, summary = routeWarningSummary(warnings, strings))
}

/**
 * The distinct kinds in distance order, as the design's subtitle reads them:
 * "Ford, private way, steps". Only the first kind keeps its capital.
 */
internal fun routeWarningSummary(
    warnings: List<RouteWarningRow>,
    strings: Strings,
): String =
    warnings
        .map { row -> row.glyph }
        .distinct()
        .mapIndexed { index, glyph ->
            val kind = routeWarningKindText(glyph, strings)
            if (index == 0) kind else kind.replaceFirstChar { it.lowercase() }
        }.joinToString(SUMMARY_SEPARATOR)

/** The list for one route, with the profile the warnings were scanned for. */
internal fun routeWarningsSheetContent(
    profile: RouteProfile,
    durationSeconds: Double,
    distanceMeters: Double,
    warnings: List<RouteWarningRow>,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
): RouteWarningsSheetContent =
    RouteWarningsSheetContent(
        title = strings.resolve(SHEET_TITLE_KEY),
        scope =
            strings.resolve(
                SCOPE_KEY,
                listOf(
                    // The profile opens the line, so it takes the sentence's capital.
                    profileLabel(profile, strings).replaceFirstChar { it.titlecase() },
                    routePlanAlternativeText(durationSeconds, strings),
                    distanceText(distanceMeters, strings, locale),
                ),
            ),
        rows =
            warnings.map { row ->
                RouteWarningRowContent(
                    glyph = row.glyph,
                    kind = routeWarningKindText(row.glyph, strings),
                    detail = routeWarningDetail(row.glyph, profile, strings),
                    distance = distanceText(row.distanceAlongRouteMeters, strings, locale),
                )
            },
        back = strings.resolve(BACK_KEY),
    )

internal fun routeWarningKindText(
    glyph: RouteWarningGlyph,
    strings: Strings,
): String =
    when (glyph) {
        RouteWarningGlyph.Ford -> strings.resolve(KIND_FORD_KEY)
        RouteWarningGlyph.Steps -> strings.resolve(KIND_STEPS_KEY)
        RouteWarningGlyph.Barrier -> strings.resolve(KIND_BARRIER_KEY)
        RouteWarningGlyph.RailwayCrossing -> strings.resolve(KIND_RAILWAY_CROSSING_KEY)
        RouteWarningGlyph.PrivateWay -> strings.resolve(KIND_PRIVATE_WAY_KEY)
    }

/**
 * The second line of a warning row. The design gives the private way the OSM
 * tag it came from and the steps the bike advice; a walker on steps gets no
 * line, because "push the bike" is not their problem.
 */
internal fun routeWarningDetail(
    glyph: RouteWarningGlyph,
    profile: RouteProfile,
    strings: Strings,
): String? =
    when {
        glyph == RouteWarningGlyph.PrivateWay -> strings.resolve(DETAIL_PRIVATE_KEY)
        glyph == RouteWarningGlyph.Steps && profile == RouteProfile.Bike -> strings.resolve(DETAIL_PUSH_BIKE_KEY)
        else -> null
    }

/**
 * The design's pictograms as text glyphs, the same convention the navigation
 * alert banners use. The circle behind the glyph carries the colour.
 */
internal fun routeWarningGlyphText(glyph: RouteWarningGlyph): String =
    when (glyph) {
        RouteWarningGlyph.Ford -> "\u2248"
        RouteWarningGlyph.Steps -> "\u2263"
        RouteWarningGlyph.Barrier -> "\u2298"
        RouteWarningGlyph.RailwayCrossing -> "\u2715"
        RouteWarningGlyph.PrivateWay -> "\u2297"
    }

/** The warning triangle the collapsed row draws before its text. */
private const val ROUTE_WARNING_SYMBOL = "\u26A0"
