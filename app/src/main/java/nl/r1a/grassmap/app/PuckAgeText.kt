package nl.r1a.grassmap.app

import android.content.Context

/**
 * The stale-fix age as one of its display forms. A fix that survives for days
 * must not count its age in minutes: past a day the label says "a long time
 * ago" instead of growing without bound.
 */
internal sealed interface PuckAgeLabel {
    data object JustNow : PuckAgeLabel

    data class Seconds(
        val seconds: Long,
    ) : PuckAgeLabel

    data class Minutes(
        val minutes: Long,
    ) : PuckAgeLabel

    data class Hours(
        val hours: Long,
    ) : PuckAgeLabel

    data object LongAgo : PuckAgeLabel
}

/** The label for [ageMillis], with the unit boundaries chosen here. */
internal fun puckAgeLabel(ageMillis: Long): PuckAgeLabel {
    val seconds = ageMillis.coerceAtLeast(0L) / MILLIS_PER_SECOND
    val minutes = seconds / SECONDS_PER_MINUTE
    val hours = minutes / MINUTES_PER_HOUR
    return when {
        seconds < AGE_NOW_SECONDS -> PuckAgeLabel.JustNow
        seconds < SECONDS_PER_MINUTE -> PuckAgeLabel.Seconds(seconds)
        minutes < MINUTES_PER_HOUR -> PuckAgeLabel.Minutes(minutes)
        hours < HOURS_PER_DAY -> PuckAgeLabel.Hours(hours)
        else -> PuckAgeLabel.LongAgo
    }
}

/** The age shown next to the grey ring, in the active language. */
internal fun formatPuckAge(
    context: Context,
    ageMillis: Long,
): String =
    when (val label = puckAgeLabel(ageMillis)) {
        PuckAgeLabel.JustNow -> context.getString(R.string.location_age_now)
        is PuckAgeLabel.Seconds -> context.getString(R.string.location_age_seconds, label.seconds)
        is PuckAgeLabel.Minutes -> context.getString(R.string.location_age_minutes, label.minutes)
        is PuckAgeLabel.Hours -> context.getString(R.string.location_age_hours, label.hours)
        PuckAgeLabel.LongAgo -> context.getString(R.string.location_age_long_ago)
    }

private const val MILLIS_PER_SECOND = 1_000L
private const val AGE_NOW_SECONDS = 10L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
