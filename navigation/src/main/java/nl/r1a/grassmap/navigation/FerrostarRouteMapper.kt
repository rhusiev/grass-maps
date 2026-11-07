package nl.r1a.grassmap.navigation

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind
import uniffi.ferrostar.BoundingBox as FerrostarBoundingBox
import uniffi.ferrostar.Route as FerrostarRoute

/**
 * Converts our route model into Ferrostar's. Ferrostar has no per-vertex
 * elevation and no typed way attributes, so those stay beside the route: this
 * conversion carries geometry, distance, waypoints and steps only.
 *
 * Our route has no per-step geometry. Each guidance instruction marks a
 * distance along the line, so the line is split there. An instruction at zero
 * keeps the whole leading interval, and a route with no instructions becomes a
 * single step. Steps are prorated by distance because our model carries only
 * the total duration reliably.
 */
internal object FerrostarRouteMapper {
    private const val METRES_PER_STEP = 0.5
    private const val MIN_GEOMETRY_POINTS = 2

    fun toFerrostar(route: Route): FerrostarRoute? {
        if (route.geometry.size < MIN_GEOMETRY_POINTS) {
            return null
        }
        val locator = PolylineLocator(route.geometry)
        val totalDistance =
            if (route.distanceMeters.isFinite() && route.distanceMeters > 0.0) {
                route.distanceMeters
            } else {
                locator.totalMeters()
            }
        val steps = stepsOf(route, locator, totalDistance)
        return FerrostarRoute(
            geometry = route.geometry.map(::coordinateOf),
            bbox = boundingBoxOf(route.geometry),
            distance = totalDistance,
            waypoints =
                listOf(
                    Waypoint(coordinate = coordinateOf(route.geometry.first()), kind = WaypointKind.BREAK),
                    Waypoint(coordinate = coordinateOf(route.geometry.last()), kind = WaypointKind.BREAK),
                ),
            steps = steps,
        )
    }

    private fun stepsOf(
        route: Route,
        locator: PolylineLocator,
        totalDistance: Double,
    ): List<RouteStep> {
        if (route.geometry.isEmpty()) {
            return emptyList()
        }
        val instructions = route.instructions.sortedBy { it.distanceAlongRouteMeters }
        val boundaries =
            buildList {
                add(0.0)
                for (instruction in instructions) {
                    val distance = instruction.distanceAlongRouteMeters.coerceIn(0.0, totalDistance)
                    if (distance > METRES_PER_STEP && distance < totalDistance - METRES_PER_STEP) {
                        add(distance)
                    }
                }
                add(totalDistance)
            }.distinct()
        if (boundaries.size < 2) {
            return listOf(stepOf(route, locator, 0.0, totalDistance, totalDistance, instructions.firstOrNull(), 0))
        }
        return boundaries
            .zipWithNext()
            .mapIndexedNotNull { index, (start, end) ->
                val length = end - start
                if (length <= METRES_PER_STEP) {
                    return@mapIndexedNotNull null
                }
                val instruction =
                    instructions.lastOrNull { it.distanceAlongRouteMeters <= start + METRES_PER_STEP }
                        ?: instructions.firstOrNull()
                stepOf(route, locator, start, end, totalDistance, instruction, index)
            }
    }

    private fun stepOf(
        route: Route,
        locator: PolylineLocator,
        start: Double,
        end: Double,
        totalDistance: Double,
        instruction: RouteInstruction?,
        index: Int,
    ): RouteStep {
        val geometry = locator.pointsBetween(start, end)
        val length = end - start
        val duration =
            if (totalDistance > 0.0) {
                route.durationSeconds * (length / totalDistance)
            } else {
                0.0
            }
        val text = instruction?.text.orEmpty()
        val maneuver = maneuverOf(instruction?.maneuver, index)
        val content =
            VisualInstructionContent(
                text = text,
                maneuverType = maneuver.type,
                maneuverModifier = maneuver.modifier,
                roundaboutExitDegrees = null,
                laneInfo = null,
                exitNumbers = emptyList(),
            )
        return RouteStep(
            geometry = geometry.map(::coordinateOf),
            distance = length,
            roadName = instruction?.streetName.orEmpty(),
            exits = emptyList(),
            instruction = text,
            visualInstructions =
                listOf(
                    VisualInstruction(
                        primaryContent = content,
                        secondaryContent = null,
                        subContent = null,
                        triggerDistanceBeforeManeuver = 0.0,
                    ),
                ),
            spokenInstructions = emptyList(),
            duration = duration,
            annotations = null,
            incidents = emptyList(),
            drivingSide = null,
            roundaboutExitNumber = null,
        )
    }

    private fun boundingBoxOf(points: List<GeoPoint>): FerrostarBoundingBox =
        FerrostarBoundingBox(
            sw =
                coordinateOf(
                    GeoPoint(
                        latitude = points.minOf { it.latitude },
                        longitude = points.minOf { it.longitude },
                    ),
                ),
            ne =
                coordinateOf(
                    GeoPoint(
                        latitude = points.maxOf { it.latitude },
                        longitude = points.maxOf { it.longitude },
                    ),
                ),
        )

    private fun coordinateOf(point: GeoPoint): GeographicCoordinate = GeographicCoordinate(lat = point.latitude, lng = point.longitude)

    /**
     * Valhalla's numeric maneuver codes, reduced to what guidance needs now.
     * T-0310 owns the full mapping and the visual instruction details.
     */
    /**
     * Valhalla's documented maneuver-type codes, mapped to Ferrostar's guidance
     * vocabulary. Ferrostar has no keep-left/right or ramp-straight types, so
     * those become the closest fork or ramp type; the modifier carries the side.
     *
     * Codes: 0 none, 1-3 start, 4-6 destination, 7 becomes, 8 continue,
     * 9-16 turns (right to left), 17-19 ramps, 20-21 exits, 22-24 stay,
     * 25 merge, 26-27 roundabout, 28-29 ferry, 30-37 transit, 38-39 building.
     */
    internal fun maneuverOf(
        maneuver: String?,
        index: Int,
    ): Maneuver {
        if (index == 0 && maneuver == null) {
            return Maneuver(ManeuverType.DEPART, ManeuverModifier.STRAIGHT)
        }
        return when (maneuver?.toIntOrNull()) {
            1 -> Maneuver(ManeuverType.DEPART, ManeuverModifier.STRAIGHT)
            2 -> Maneuver(ManeuverType.DEPART, ManeuverModifier.RIGHT)
            3 -> Maneuver(ManeuverType.DEPART, ManeuverModifier.LEFT)
            4 -> Maneuver(ManeuverType.ARRIVE, ManeuverModifier.STRAIGHT)
            5 -> Maneuver(ManeuverType.ARRIVE, ManeuverModifier.RIGHT)
            6 -> Maneuver(ManeuverType.ARRIVE, ManeuverModifier.LEFT)
            7 -> Maneuver(ManeuverType.NEW_NAME, ManeuverModifier.STRAIGHT)
            8, 22 -> Maneuver(ManeuverType.CONTINUE, ManeuverModifier.STRAIGHT)
            9 -> Maneuver(ManeuverType.TURN, ManeuverModifier.SLIGHT_RIGHT)
            10 -> Maneuver(ManeuverType.TURN, ManeuverModifier.RIGHT)
            11 -> Maneuver(ManeuverType.TURN, ManeuverModifier.SHARP_RIGHT)
            12, 13 -> Maneuver(ManeuverType.TURN, ManeuverModifier.U_TURN)
            14 -> Maneuver(ManeuverType.TURN, ManeuverModifier.SHARP_LEFT)
            15 -> Maneuver(ManeuverType.TURN, ManeuverModifier.LEFT)
            16 -> Maneuver(ManeuverType.TURN, ManeuverModifier.SLIGHT_LEFT)
            17 -> Maneuver(ManeuverType.ON_RAMP, ManeuverModifier.STRAIGHT)
            18 -> Maneuver(ManeuverType.ON_RAMP, ManeuverModifier.SLIGHT_RIGHT)
            19 -> Maneuver(ManeuverType.ON_RAMP, ManeuverModifier.SLIGHT_LEFT)
            20 -> Maneuver(ManeuverType.OFF_RAMP, ManeuverModifier.RIGHT)
            21 -> Maneuver(ManeuverType.OFF_RAMP, ManeuverModifier.LEFT)
            23 -> Maneuver(ManeuverType.FORK, ManeuverModifier.SLIGHT_RIGHT)
            24 -> Maneuver(ManeuverType.FORK, ManeuverModifier.SLIGHT_LEFT)
            25 -> Maneuver(ManeuverType.MERGE, ManeuverModifier.STRAIGHT)
            26 -> Maneuver(ManeuverType.ROUNDABOUT, ManeuverModifier.STRAIGHT)
            27 -> Maneuver(ManeuverType.EXIT_ROUNDABOUT, ManeuverModifier.STRAIGHT)
            28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39 ->
                Maneuver(ManeuverType.NOTIFICATION, ManeuverModifier.STRAIGHT)
            else -> Maneuver(ManeuverType.CONTINUE, ManeuverModifier.STRAIGHT)
        }
    }
}

/** One Valhalla maneuver code reduced to the type and side guidance needs. */
internal data class Maneuver(
    val type: ManeuverType,
    val modifier: ManeuverModifier,
)
