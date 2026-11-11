package nl.r1a.grassmap.plugins.guidance.ui

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import nl.r1a.grassmap.plugin.api.service.Strings
import java.time.Instant
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The app's English bundle, close enough to assert the panel's own text. */
internal object TestStrings : Strings {
    private val templates =
        mapOf(
            GuidanceStrings.NEXT_STOP to "Next stop",
            GuidanceStrings.ARRIVE to "Arrive at the destination",
            GuidanceStrings.ARRIVED to "Arrived",
            GuidanceStrings.OFF_ROUTE to "Off route",
            GuidanceStrings.IN to "in %1\$s, %2\$s",
            GuidanceStrings.THEN_IN to "then in %1\$s, %2\$s",
            GuidanceStrings.METRES to "%1\$s m",
            GuidanceStrings.KILOMETRES to "%1\$s km",
            GuidanceStrings.MINUTES to "%1\$s min",
            GuidanceStrings.HOURS to "%1\$s h",
            GuidanceStrings.HOURS_MINUTES to "%1\$s h %2\$s min",
        )

    override fun resolve(
        key: String,
        arguments: List<String>,
    ): String {
        val template = templates[key] ?: return key
        return String.format(Locale.US, template, *arguments.toTypedArray())
    }

    override fun contains(key: String): Boolean = key in templates
}

/** One `trkpt` of the committed GPX fixture. */
internal data class TrackPoint(
    val position: GeoPoint,
    val timeMillis: Long,
)

/**
 * The fixture route for the acceptance replay: the committed GPX line, three
 * legs with two intermediate stops, and one instruction per maneuver including
 * the two leg ends and the destination.
 *
 * Speeds differ per leg so the time model cannot pass as one average speed.
 */
internal class ReplayFixture(
    val track: List<TrackPoint>,
    val route: Route,
    val cumulativeMeters: List<Double>,
    val stopDistancesMeters: List<Double>,
    val sampleIndices: List<Int>,
) {
    val totalMeters: Double get() = cumulativeMeters.last()

    /** The distance along the route at a sample, the model's own coordinate. */
    fun alongMeters(index: Int): Double = cumulativeMeters[index]
}

internal fun replayFixture(): ReplayFixture {
    val track = parseGpx(resource(FIXTURE))
    val geometry = track.map { it.position }
    val cumulative = cumulativeMeters(geometry)
    val total = cumulative.last()
    val stopOne = cumulative[STOP_ONE_INDEX]
    val stopTwo = cumulative[STOP_TWO_INDEX]
    val speedAt: (Double) -> Double = { distance ->
        when {
            distance < stopOne -> LEG_ONE_SPEED
            distance < stopTwo -> LEG_TWO_SPEED
            else -> LEG_THREE_SPEED
        }
    }

    val instructionIndices = listOf(0, 6, 12, STOP_ONE_INDEX, 28, 34, STOP_TWO_INDEX, 48, 54, geometry.lastIndex)
    val texts =
        listOf(
            "Depart",
            "Turn right onto A",
            "Turn left onto B",
            "Arrive at stop one",
            "Turn right onto C",
            "Turn left onto D",
            "Arrive at stop two",
            "Turn right onto E",
            "Turn left onto F",
            "Arrive at the destination",
        )
    val instructions =
        instructionIndices.mapIndexed { position, index ->
            val distance = cumulative[index]
            val duration =
                if (position == instructionIndices.lastIndex) {
                    0.0
                } else {
                    (cumulative[instructionIndices[position + 1]] - distance) / speedAt(distance)
                }
            RouteInstruction(
                text = texts[position],
                streetName = null,
                distanceAlongRouteMeters = distance,
                durationSeconds = duration,
                maneuver = if (texts[position].startsWith("Arrive")) "4" else "9",
            )
        }
    val segments =
        listOf(
            segment(0, STOP_ONE_INDEX, stopOne, stopOne / speedAt(0.0)),
            segment(
                STOP_ONE_INDEX,
                STOP_TWO_INDEX,
                stopTwo - stopOne,
                (stopTwo - stopOne) / speedAt(stopOne),
            ),
            segment(STOP_TWO_INDEX, geometry.lastIndex, total - stopTwo, (total - stopTwo) / speedAt(stopTwo)),
        )
    val route =
        Route(
            geometry = geometry,
            distanceMeters = total,
            durationSeconds = segments.sumOf { it.durationSeconds },
            segments = segments,
            instructions = instructions,
        )
    return ReplayFixture(
        track = track,
        route = route,
        cumulativeMeters = cumulative,
        stopDistancesMeters = listOf(stopOne, stopTwo),
        sampleIndices = SAMPLE_INDICES,
    )
}

private fun segment(
    startIndex: Int,
    endIndex: Int,
    distanceMeters: Double,
    durationSeconds: Double,
): RouteSegment =
    RouteSegment(
        startIndex = startIndex,
        endIndex = endIndex,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
    )

/** One straight route for the edge cases, so their numbers are hand-checkable. */
internal fun straightRoute(
    distanceMeters: Double = 300.0,
    durationSeconds: Double = 300.0,
    instructions: List<RouteInstruction> = emptyList(),
    segments: List<RouteSegment> = emptyList(),
): Route =
    Route(
        geometry = listOf(GeoPoint(49.8400, 24.0300), GeoPoint(49.8427, 24.0300)),
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        segments = segments,
        instructions = instructions,
    )

internal fun instruction(
    text: String,
    distanceMeters: Double,
    durationSeconds: Double,
): RouteInstruction = RouteInstruction(text, null, distanceMeters, durationSeconds, null)

internal fun leg(
    distanceMeters: Double,
    durationSeconds: Double,
): RouteSegment = RouteSegment(0, 1, distanceMeters, durationSeconds)

/** A router that must not be called; a replay that reroutes fails the test loudly. */
internal object NoRoutingEngine : RoutingEngine {
    override val contract: ProviderContract = RoutingEngineContract.REFERENCE

    override fun capabilities(): RoutingCapabilities =
        RoutingCapabilities(
            profiles = setOf(RouteProfile.Walk),
            modifierKinds = emptySet(),
            maxAlternatives = 0,
            maxDistanceMeters = 250_000.0,
        )

    override suspend fun route(
        request: RouteRequest,
        cancellation: CancellationHandle,
    ): Outcome<List<Route>> = throw AssertionError("the replay rerouted unexpectedly: $request")
}

internal fun resource(name: String): String =
    requireNotNull(TrackPoint::class.java.classLoader?.getResourceAsStream(name)) { "missing test resource $name" }
        .use { it.readBytes().decodeToString() }

internal fun parseGpx(gpx: String): List<TrackPoint> {
    val pointPattern = Regex("<trkpt\\b([^>]*)>(.*?)</trkpt>", RegexOption.DOT_MATCHES_ALL)
    val timePattern = Regex("<time>([^<]+)</time>")
    return pointPattern
        .findAll(gpx)
        .mapNotNull { match ->
            val attributes = match.groupValues[1]
            val latitude = attribute(attributes, "lat")?.toDoubleOrNull() ?: return@mapNotNull null
            val longitude = attribute(attributes, "lon")?.toDoubleOrNull() ?: return@mapNotNull null
            val time = timePattern.find(match.groupValues[2])?.groupValues?.get(1)
            TrackPoint(
                position = GeoPoint(latitude, longitude),
                timeMillis = time?.let { Instant.parse(it).toEpochMilli() } ?: 0L,
            )
        }.toList()
}

private fun attribute(
    attributes: String,
    name: String,
): String? = Regex("\\b" + name + "\\s*=\\s*\"([^\"]*)\"").find(attributes)?.groupValues?.get(1)

/** The IUGG mean radius, independent of the panel's own geometry constant. */
internal fun haversineMeters(
    from: GeoPoint,
    to: GeoPoint,
): Double {
    val fromLat = Math.toRadians(from.latitude)
    val toLat = Math.toRadians(to.latitude)
    val deltaLat = toLat - fromLat
    val deltaLon = Math.toRadians(to.longitude - from.longitude)
    val a = sin(deltaLat / 2) * sin(deltaLat / 2) + cos(fromLat) * cos(toLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

internal fun cumulativeMeters(points: List<GeoPoint>): List<Double> {
    val cumulative = MutableList(points.size) { 0.0 }
    for (index in 1 until points.size) {
        cumulative[index] = cumulative[index - 1] + haversineMeters(points[index - 1], points[index])
    }
    return cumulative
}

private const val FIXTURE = "location/mock-walk.gpx"
private const val STOP_ONE_INDEX = 20
private const val STOP_TWO_INDEX = 40
private const val EARTH_RADIUS_METERS = 6_371_008.8
private const val LEG_ONE_SPEED = 1.2
private const val LEG_TWO_SPEED = 1.6
private const val LEG_THREE_SPEED = 1.0

/**
 * Every third committed point from the first to the fifty-fifth, twenty in all.
 * The set spans all three legs, both leg ends and each instruction, rather than
 * taking one sample per turn.
 */
internal val SAMPLE_INDICES: List<Int> =
    listOf(0, 3, 6, 9, 12, 15, 18, 20, 23, 26, 29, 32, 35, 38, 40, 43, 46, 49, 52, 55)
