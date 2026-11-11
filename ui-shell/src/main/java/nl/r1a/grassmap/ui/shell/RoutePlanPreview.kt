package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.service.Strings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The four things the preview shows, and nothing else: time, distance,
 * arrival and the Start action (T-0302). Elevation, slope, surface and way
 * detail belong to later tasks and must not be added here.
 */
enum class RoutePreviewField {
    Time,
    Distance,
    Arrival,
    Start,
}

/** One preview item. The Start item is the action; the other three are values. */
data class RoutePreviewEntry(
    val field: RoutePreviewField,
    val text: String,
)

/** The preview card. Construction fixes the list at four entries. */
data class RoutePreview(
    val entries: List<RoutePreviewEntry>,
) {
    init {
        require(entries.map { it.field } == FIELDS) { "the preview has exactly four fields" }
    }

    val time: String get() = entries[0].text
    val distance: String get() = entries[1].text
    val arrival: String get() = entries[2].text
    val start: String get() = entries[3].text

    private companion object {
        val FIELDS =
            listOf(
                RoutePreviewField.Time,
                RoutePreviewField.Distance,
                RoutePreviewField.Arrival,
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
 */
fun routePlanPreview(
    state: RoutePlanState,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): RoutePreview? {
    val option = state.selected ?: return null
    return RoutePreview(
        entries =
            listOf(
                RoutePreviewEntry(RoutePreviewField.Time, durationText(option.durationSeconds, strings)),
                RoutePreviewEntry(
                    RoutePreviewField.Distance,
                    distanceText(option.distanceMeters, strings, locale),
                ),
                RoutePreviewEntry(
                    RoutePreviewField.Arrival,
                    ARRIVAL_FORMAT.format(Instant.ofEpochMilli(option.arrivalEpochMillis).atZone(zone)),
                ),
                RoutePreviewEntry(RoutePreviewField.Start, strings.resolve(START_KEY)),
            ),
    )
}

/** The text for one alternative, so the chips use the same duration rule. */
fun routePlanAlternativeText(
    durationSeconds: Double,
    strings: Strings,
): String = durationText(durationSeconds, strings)

private fun durationText(
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
private const val START_KEY = "route_preview_start"
private const val MINUTES_KEY = "route_preview_minutes"
private const val HOURS_KEY = "route_preview_hours"
private const val HOURS_MINUTES_KEY = "route_preview_hours_minutes"
private const val METRES_KEY = "route_preview_metres"
private const val KILOMETRES_KEY = "route_preview_kilometres"
