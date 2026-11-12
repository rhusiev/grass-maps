package nl.r1a.grassmap.plugins.guidance.ui

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProgress
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.RouteTurnStep
import nl.r1a.grassmap.plugin.ui.api.routeTurns
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

/**
 * What the guidance panel shows, in the guidance state's own terms.
 *
 * The app maps a published `NavigationState` onto this: [status] is
 * [GuidanceStatus.Arrived] when the phase is arrival and [GuidanceStatus.OffRoute]
 * when the relation is off route; [alongMeters] is the route total minus the
 * state's remaining distance. The panel never decides either of those itself.
 */
data class GuidanceInput(
    val route: Route?,
    val alongMeters: Double,
    val status: GuidanceStatus = GuidanceStatus.Following,
)

/** The guidance relation the panel is told about; it does not measure it. */
enum class GuidanceStatus {
    Following,
    OffRoute,
    Arrived,
}

/**
 * The four facts the guidance panel shows, and nothing else: the next turn, the
 * second next turn, and the distance and the time to the next intermediate
 * stop. A fifth fact would need a fifth value here, which is the point of the
 * enum.
 */
enum class GuidancePanelField {
    NextTurn,
    SecondTurn,
    NextStopDistance,
    NextStopTime,
}

/**
 * One shown fact. [title] is the turn's own text or the stop row's label;
 * [detail] is the formatted distance and time. [distanceMeters] and
 * [durationSeconds] keep the model's numbers so a test can measure the panel
 * against the route instead of parsing its text.
 */
data class GuidancePanelEntry(
    val field: GuidancePanelField,
    val title: String,
    val detail: String? = null,
    val distanceMeters: Double? = null,
    val durationSeconds: Double? = null,
    val streetName: String? = null,
    val maneuver: String? = null,
)

/** A status the panel reports beside its facts, never instead of one. */
enum class GuidanceNotice {
    OffRoute,
}

/**
 * The panel's facts in canonical order. Construction fixes the vocabulary:
 * each field appears at most once, every field is one of the four, and the
 * order is the enum's order. Stop rows are absent when the route has no via;
 * that is fewer facts, not a different one.
 */
data class GuidancePanel(
    val entries: List<GuidancePanelEntry>,
    val notice: GuidanceNotice? = null,
) {
    init {
        require(entries.size <= FIELDS.size) { "the guidance panel has at most four facts" }
        val fields = entries.map { it.field }
        require(fields.distinct().size == fields.size) { "each guidance fact appears at most once" }
        require(fields == fields.sortedBy { it.ordinal }) { "the guidance facts are in canonical order" }
    }

    private companion object {
        val FIELDS = GuidancePanelField.entries
    }
}

/** The keys this panel resolves; the app bundles carry English and Ukrainian. */
object GuidanceStrings {
    const val PLUGIN_NAME = "plugin.guidance_ui.name"
    const val PLUGIN_DESCRIPTION = "plugin.guidance_ui.description"
    const val NEXT_STOP = "guidance_panel_next_stop"
    const val ARRIVE = "guidance_panel_arrive"
    const val ARRIVED = "guidance_panel_arrived"
    const val OFF_ROUTE = "guidance_panel_off_route"
    const val IN = "guidance_panel_in"
    const val THEN_IN = "guidance_panel_then_in"
    const val METRES = "guidance_panel_metres"
    const val KILOMETRES = "guidance_panel_kilometres"
    const val MINUTES = "guidance_panel_minutes"
    const val HOURS = "guidance_panel_hours"
    const val HOURS_MINUTES = "guidance_panel_hours_minutes"
    const val SCREEN_THEN = "guidance_screen_then"
}

/**
 * Derives the panel from one published guidance position.
 *
 * The function is pure: no camera, no location stream, no clock. The caller runs
 * it once per published navigation state, not on a map draw or gesture frame.
 * It returns null when there is no route, which is also when the panel is not
 * drawn.
 *
 * Turns come from the shared [routeTurns] enumeration, so the panel and the
 * planner's stepper walk the engine's instructions once. A turn passed by more
 * than one metre is behind the driver; the next two ahead are the panel's two
 * turn facts. Time is [RouteProgress.durationBetween], the same span model the
 * session counts its remaining time from.
 *
 * An intermediate stop is a leg end in [Route.segments]. A route whose segments
 * do not form a consistent leg model has no stop fact: a guessed stop is worse
 * than a missing row. Arrival shows the arrival fact alone; off route adds a
 * notice beside the same facts.
 */
fun guidancePanel(
    input: GuidanceInput,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
): GuidancePanel? {
    val route = input.route ?: return null
    val progress = RouteProgress.of(route)
    val total = progress.totalDistanceMeters
    if (total <= 0.0) {
        return null
    }
    val along = input.alongMeters.coerceIn(0.0, total)
    if (input.status == GuidanceStatus.Arrived) {
        return GuidancePanel(
            entries =
                listOf(
                    GuidancePanelEntry(
                        field = GuidancePanelField.NextTurn,
                        title = strings.resolve(GuidanceStrings.ARRIVED),
                    ),
                ),
        )
    }
    val arriveText = strings.resolve(GuidanceStrings.ARRIVE)
    val turns =
        routeTurns(
            geometry = route.geometry,
            distanceMeters = total,
            durationSeconds = route.durationSeconds,
            instructions = route.instructions,
            arriveText = arriveText,
        )
    val ahead = turns.filter { it.distanceMeters > along + PASSED_METERS }
    val next = ahead.getOrNull(0) ?: arrivalTurn(route, total, arriveText)
    val second = ahead.getOrNull(1)
    val entries = mutableListOf<GuidancePanelEntry>()
    entries += turnEntry(GuidancePanelField.NextTurn, next, along, progress, strings, locale)
    if (second != null) {
        entries += turnEntry(GuidancePanelField.SecondTurn, second, along, progress, strings, locale)
    }
    val stop = legBoundaries(route, total).firstOrNull { it > along + PASSED_METERS }
    if (stop != null) {
        val distance = (stop - along).coerceAtLeast(0.0)
        val seconds = progress.durationBetween(along, stop)
        entries +=
            GuidancePanelEntry(
                field = GuidancePanelField.NextStopDistance,
                title = strings.resolve(GuidanceStrings.NEXT_STOP),
                detail = distanceText(distance, strings, locale),
                distanceMeters = distance,
            )
        entries +=
            GuidancePanelEntry(
                field = GuidancePanelField.NextStopTime,
                title = strings.resolve(GuidanceStrings.NEXT_STOP),
                detail = durationText(seconds, strings),
                durationSeconds = seconds,
            )
    }
    return GuidancePanel(
        entries = entries,
        notice = if (input.status == GuidanceStatus.OffRoute) GuidanceNotice.OffRoute else null,
    )
}

private fun turnEntry(
    field: GuidancePanelField,
    turn: RouteTurnStep,
    along: Double,
    progress: RouteProgress,
    strings: Strings,
    locale: Locale,
): GuidancePanelEntry {
    val distance = (turn.distanceMeters - along).coerceAtLeast(0.0)
    val seconds = progress.durationBetween(along, turn.distanceMeters)
    val format = if (field == GuidancePanelField.SecondTurn) GuidanceStrings.THEN_IN else GuidanceStrings.IN
    return GuidancePanelEntry(
        field = field,
        title = turn.text,
        detail = strings.resolve(format, listOf(distanceText(distance, strings, locale), durationText(seconds, strings))),
        distanceMeters = distance,
        durationSeconds = seconds,
        streetName = turn.streetName,
        maneuver = turn.maneuver,
    )
}

/**
 * The arrival fact once every instruction is behind the position but the phase
 * has not turned to arrival yet. It keeps the four-fact vocabulary: the next
 * turn is the destination, at the route's own end.
 */
private fun arrivalTurn(
    route: Route,
    total: Double,
    text: String,
): RouteTurnStep =
    RouteTurnStep(
        index = Int.MAX_VALUE,
        text = text,
        distanceMeters = total,
        durationSeconds = 0.0,
        position = route.geometry.lastOrNull() ?: GeoPoint(0.0, 0.0),
    )

/**
 * The distance along the route at each intermediate stop, excluding the
 * destination. Segments are the route's legs, one per requested hop; a chain
 * that does not add up to the route total is not a leg model and yields none.
 *
 * Segments that carry per-edge attributes came from an annotate pass: they are
 * graph edges, not legs, and treating each as a stop would invent stops on a
 * route that has none. They yield no stop fact; the task that schedules
 * annotation must keep the leg model if the panel is to show one.
 */
private fun legBoundaries(
    route: Route,
    total: Double,
): List<Double> {
    if (route.segments.size < MIN_LEGS || route.segments.any { it.attributes.isNotEmpty() }) {
        return emptyList()
    }
    val distances = route.segments.map { it.distanceMeters }
    if (distances.any { !it.isFinite() || it < 0.0 }) {
        return emptyList()
    }
    val tolerance = maxOf(1.0, total * LEG_TOLERANCE)
    if (abs(distances.sum() - total) > tolerance) {
        return emptyList()
    }
    var running = 0.0
    return distances.dropLast(1).map { distance ->
        running += distance
        running
    }
}

private fun distanceText(
    meters: Double,
    strings: Strings,
    locale: Locale,
): String = guidanceDistanceText(meters, strings, locale)

/** The distance the panel and the navigation screen format, in the user's units. */
fun guidanceDistanceText(
    meters: Double,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
): String =
    if (meters < METRES_PER_KILOMETRE) {
        strings.resolve(GuidanceStrings.METRES, listOf(round(meters).toLong().toString()))
    } else {
        strings.resolve(
            GuidanceStrings.KILOMETRES,
            listOf(String.format(locale, "%.1f", meters / METRES_PER_KILOMETRE)),
        )
    }

private fun durationText(
    seconds: Double,
    strings: Strings,
): String = guidanceDurationText(seconds, strings)

/** The duration the panel and the navigation screen format. */
fun guidanceDurationText(
    seconds: Double,
    strings: Strings,
): String {
    val totalMinutes = ceil(seconds / SECONDS_PER_MINUTE).toLong().coerceAtLeast(0L)
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return when {
        hours == 0L -> strings.resolve(GuidanceStrings.MINUTES, listOf(minutes.toString()))
        minutes == 0L -> strings.resolve(GuidanceStrings.HOURS, listOf(hours.toString()))
        else -> strings.resolve(GuidanceStrings.HOURS_MINUTES, listOf(hours.toString(), minutes.toString()))
    }
}

/** A turn within one metre of the position is the one being made, not the next. */
private const val PASSED_METERS = 1.0
private const val MIN_LEGS = 2
private const val LEG_TOLERANCE = 0.005
private const val METRES_PER_KILOMETRE = 1_000.0
private const val SECONDS_PER_MINUTE = 60.0
private const val MINUTES_PER_HOUR = 60L
