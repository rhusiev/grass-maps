package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.RoutePlanOption
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.service.Strings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the preview card shows: time, distance, arrival, Save and Start (T-0302,
 * Save in T-0411). Elevation, slope, surface and way detail belong to later
 * tasks and must not be added here. The slope-aware model's name (T-0407) sits
 * inside the Time text, so it is not a field of its own.
 *
 * The design allows four elements in this card. Save is a fifth, taken as a
 * product decision on 2026-11-14 because the library needs an in-UI way in and
 * the card is where the route is. Start stays the primary action, so the two
 * actions sit on the card's own trailing row, Save before Start.
 */
enum class RoutePreviewField {
    Time,
    Distance,
    Arrival,
    Save,
    Start,
}

/** One preview item. Save and Start are actions; the first three are values. */
data class RoutePreviewEntry(
    val field: RoutePreviewField,
    val text: String,
)

/**
 * A slope-aware time and the model that produced it (T-0407). The app computes
 * it from the route's profile; the shell only formats the seconds and names the
 * model beside them.
 */
data class RoutePreviewTime(
    val seconds: Double,
    val model: String,
)

/** The preview card. Construction fixes the list at its five entries. */
data class RoutePreview(
    val entries: List<RoutePreviewEntry>,
) {
    init {
        require(entries.map { it.field } == FIELDS) { "the preview has exactly five fields" }
    }

    val time: String get() = entries[0].text
    val distance: String get() = entries[1].text
    val arrival: String get() = entries[2].text
    val save: String get() = entries[3].text
    val start: String get() = entries[4].text

    private companion object {
        val FIELDS =
            listOf(
                RoutePreviewField.Time,
                RoutePreviewField.Distance,
                RoutePreviewField.Arrival,
                RoutePreviewField.Save,
                RoutePreviewField.Start,
            )
    }
}

/**
 * Derives the preview from the published plan. Null while there is no selected
 * route: a plan that is still picking or planning shows its own state line, not
 * an empty card.
 *
 * The arrival is formatted from the clock time the planner recorded, so every
 * view of one route shows the same arrival instead of drifting with the clock.
 *
 * [time] is the slope-aware estimate (T-0407). When present it replaces the
 * engine's duration in the Time entry and moves the arrival by the same amount,
 * so the two totals stay one answer. It also names the model beside the
 * duration.
 */
fun routePlanPreview(
    state: RoutePlanState,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
    time: RoutePreviewTime? = null,
): RoutePreview? {
    val option = state.selected ?: return null
    val seconds = time?.seconds ?: option.durationSeconds
    return RoutePreview(
        entries =
            listOf(
                RoutePreviewEntry(RoutePreviewField.Time, timeText(seconds, time?.model, strings)),
                RoutePreviewEntry(
                    RoutePreviewField.Distance,
                    distanceText(option.distanceMeters, strings, locale),
                ),
                RoutePreviewEntry(
                    RoutePreviewField.Arrival,
                    ARRIVAL_FORMAT.format(Instant.ofEpochMilli(arrivalMillis(option, seconds)).atZone(zone)),
                ),
                RoutePreviewEntry(RoutePreviewField.Save, strings.resolve(SAVE_KEY)),
                RoutePreviewEntry(RoutePreviewField.Start, strings.resolve(START_KEY)),
            ),
    )
}

/**
 * The arrival that belongs to [seconds]: the recorded arrival is the engine's
 * departure plus its duration, so a different duration moves it by the
 * difference. A fallback that equals the engine's duration keeps the recorded
 * arrival exactly.
 */
private fun arrivalMillis(
    option: RoutePlanOption,
    seconds: Double,
): Long {
    val departure = option.arrivalEpochMillis - (option.durationSeconds * MILLIS_PER_SECOND).roundToLong()
    return departure + (seconds * MILLIS_PER_SECOND).roundToLong()
}

private fun timeText(
    seconds: Double,
    model: String?,
    strings: Strings,
): String {
    val duration = durationText(seconds, strings)
    return if (model == null) duration else duration + MODEL_SEPARATOR + model
}

/** The text for one alternative, so the chips use the same duration rule. */
fun routePlanAlternativeText(
    durationSeconds: Double,
    strings: Strings,
): String = durationText(durationSeconds, strings)

internal fun durationText(
    seconds: Double,
    strings: Strings,
): String {
    val totalMinutes =
        kotlin.math
            .ceil(seconds / SECONDS_PER_MINUTE)
            .toLong()
            .coerceAtLeast(1L)
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return when {
        hours == 0L -> strings.resolve(MINUTES_KEY, listOf(minutes.toString()))
        minutes == 0L -> strings.resolve(HOURS_KEY, listOf(hours.toString()))
        else -> strings.resolve(HOURS_MINUTES_KEY, listOf(hours.toString(), minutes.toString()))
    }
}

internal fun distanceText(
    meters: Double,
    strings: Strings,
    locale: Locale,
): String =
    if (meters < METRES_PER_KILOMETRE) {
        strings.resolve(METRES_KEY, listOf(meters.roundToLong().toString()))
    } else {
        strings.resolve(
            KILOMETRES_KEY,
            listOf(String.format(locale, "%.1f", meters / METRES_PER_KILOMETRE)),
        )
    }

private fun Double.roundToLong(): Long = kotlin.math.round(this).toLong()

private val ARRIVAL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private const val SECONDS_PER_MINUTE = 60.0
private const val MINUTES_PER_HOUR = 60L
private const val METRES_PER_KILOMETRE = 1_000.0
private const val MILLIS_PER_SECOND = 1_000.0
private const val MODEL_SEPARATOR = " · "
private const val START_KEY = "route_preview_start"
private const val SAVE_KEY = "route_preview_save"
private const val MINUTES_KEY = "route_preview_minutes"
private const val HOURS_KEY = "route_preview_hours"
private const val HOURS_MINUTES_KEY = "route_preview_hours_minutes"
private const val METRES_KEY = "route_preview_metres"
private const val KILOMETRES_KEY = "route_preview_kilometres"
