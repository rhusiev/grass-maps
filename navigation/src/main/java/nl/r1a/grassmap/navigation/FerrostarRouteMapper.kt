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
        val content =
            VisualInstructionContent(
                text = text,
                maneuverType = maneuverTypeOf(instruction?.maneuver, index),
                maneuverModifier = maneuverModifierOf(instruction?.maneuver),
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
    private fun maneuverTypeOf(
        maneuver: String?,
        index: Int,
    ): ManeuverType {
        if (index == 0 && maneuver == null) {
            return ManeuverType.DEPART
        }
        return when (maneuver?.toIntOrNull()) {
            null -> ManeuverType.CONTINUE
            1, 2, 3 -> ManeuverType.DEPART
            4, 5, 6 -> ManeuverType.ARRIVE
            10, 24, 25, 26 -> ManeuverType.CONTINUE
            11, 12, 13, 16, 17, 18 -> ManeuverType.TURN
            19, 20, 21, 22, 23 -> ManeuverType.FORK
            27 -> ManeuverType.MERGE
            28, 29, 30, 31 -> ManeuverType.ROUNDABOUT
            else -> ManeuverType.CONTINUE
        }
    }

    private fun maneuverModifierOf(maneuver: String?): ManeuverModifier =
        when (maneuver?.toIntOrNull()) {
            2, 5, 11, 20, 25 -> ManeuverModifier.SLIGHT_RIGHT
            3, 6, 18, 21, 26 -> ManeuverModifier.SLIGHT_LEFT
            12, 16 -> ManeuverModifier.RIGHT
            17, 13 -> ManeuverModifier.LEFT
            14, 15 -> ManeuverModifier.U_TURN
            else -> ManeuverModifier.STRAIGHT
        }
}
