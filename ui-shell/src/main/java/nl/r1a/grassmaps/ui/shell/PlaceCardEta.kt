package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.api.unit.UnitText
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The straight-line distance and the walking-time estimate in the place card
 * subtitle (T-0612). Both come from local arithmetic: opening a card never
 * calls the router. [walkText] says the time is an estimate because the
 * distance is straight-line and the pace is fixed.
 */
data class PlaceCardEta(
    val distanceText: String,
    val walkText: String,
) {
    companion object {
        /** IUGG mean Earth radius, metres - the same value `:map` uses. */
        const val EARTH_RADIUS_METERS: Double = 6_371_000.0

        /** A steady walking pace for the estimate only, metres per second (4.5 km/h). */
        const val WALKING_SPEED_METERS_PER_SECOND: Double = 1.25
    }
}

/**
 * The great-circle distance from [origin] to [destination], in metres.
 *
 * ui-shell cannot depend on `:map`, so the haversine formula lives here with
 * the same named radius; both readouts use the same Earth model.
 */
internal fun greatCircleMeters(
    origin: GeoPoint,
    destination: GeoPoint,
): Double {
    val fromLat = Math.toRadians(origin.latitude)
    val toLat = Math.toRadians(destination.latitude)
    val deltaLat = toLat - fromLat
    val deltaLon = Math.toRadians(destination.longitude - origin.longitude)
    val halfChord =
        sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(fromLat) * cos(toLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return 2.0 * PlaceCardEta.EARTH_RADIUS_METERS * asin(sqrt(halfChord.coerceIn(0.0, 1.0)))
}

/**
 * The estimate for one place, or null when either end of the walk is unknown.
 *
 * Distance and duration go through the app's one formatter each: [UnitText]
 * for the length and [durationText] for the minutes. A missing position yields
 * no fake ETA.
 */
internal fun placeCardEta(
    origin: GeoPoint?,
    destination: GeoPoint?,
    units: UnitSystem,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
): PlaceCardEta? {
    if (origin == null || destination == null) {
        return null
    }
    val meters = greatCircleMeters(origin, destination)
    val seconds = meters / PlaceCardEta.WALKING_SPEED_METERS_PER_SECOND
    return PlaceCardEta(
        distanceText = UnitText.distance(meters, units, strings, locale),
        walkText = strings.resolve(PLACE_CARD_WALK_ESTIMATE, listOf(durationText(seconds, strings))),
    )
}

/** The kind-only subtitle when the walk is unknown, else kind, distance and estimate. */
internal fun placeCardSubtitle(
    kind: String?,
    origin: GeoPoint?,
    destination: GeoPoint?,
    units: UnitSystem,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
): String? {
    val eta = placeCardEta(origin, destination, units, strings, locale)
    return listOfNotNull(kind, eta?.distanceText, eta?.walkText)
        .joinToString(SUBTITLE_SEPARATOR)
        .takeIf { it.isNotEmpty() }
}

internal const val PLACE_CARD_WALK_ESTIMATE = "place_card_walk_estimate"

private const val SUBTITLE_SEPARATOR = " · "
