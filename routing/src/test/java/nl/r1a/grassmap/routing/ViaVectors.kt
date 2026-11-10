package nl.r1a.grassmap.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteSegment

/**
 * The captured three-via answers in `test-data/routing/via-vectors.json`.
 *
 * One case is the same three stops in one order, the next is a reordered chain
 * and the last drops a stop, for each of the three v1 profiles. The lines are
 * the engine's own answers, so the measurements below run on what Valhalla
 * returned rather than on a line a test drew.
 */
internal object ViaVectors {
    private const val RESOURCE = "routing/via-vectors.json"

    lateinit var start: GeoPoint
        private set

    lateinit var destination: GeoPoint
        private set

    lateinit var vias: List<GeoPoint>
        private set

    lateinit var viasByName: List<String>
        private set

    lateinit var cases: List<ViaCase>
        private set

    init {
        val root = Json.parseToJsonElement(read()).jsonObject
        start = root.getValue("start").jsonArray.toPoint()
        destination = root.getValue("destination").jsonArray.toPoint()
        val viaObjects = root.getValue("vias").jsonArray.map { it.jsonObject }
        vias = viaObjects.map { via -> GeoPoint(via.getValue("lat").jsonPrimitive.double, via.getValue("lon").jsonPrimitive.double) }
        viasByName = viaObjects.map { via -> via.getValue("label").jsonPrimitive.content }
        cases = root.getValue("cases").jsonArray.map { it.jsonObject.toCase() }
    }

    /** The case that visits [order] on [profile], or null when none was captured. */
    fun caseFor(
        profile: RouteProfile,
        order: List<Int>,
    ): ViaCase? = cases.firstOrNull { case -> case.profile == profile && case.viaOrder == order }

    private fun read(): String =
        checkNotNull(ViaVectors::class.java.classLoader?.getResource(RESOURCE)) {
            "missing $RESOURCE"
        }.readText()

    private fun JsonObject.toCase(): ViaCase {
        val profile =
            when (getValue("profile").jsonPrimitive.content) {
                "walk" -> RouteProfile.Walk
                "bike" -> RouteProfile.Bike
                "hike" -> RouteProfile.Hike
                else -> error("unknown profile in $RESOURCE")
            }
        return ViaCase(
            id = getValue("id").jsonPrimitive.content,
            profile = profile,
            viaOrder = getValue("viaOrder").jsonArray.map { it.jsonPrimitive.int },
            route =
                Route(
                    geometry = getValue("geometry").jsonArray.map { it.jsonArray.toPoint() },
                    distanceMeters = getValue("distanceMeters").jsonPrimitive.double,
                    durationSeconds = getValue("durationSeconds").jsonPrimitive.double,
                    segments = getValue("legs").jsonArray.map { it.jsonObject.toSegment() },
                ),
        )
    }

    private fun JsonObject.toSegment(): RouteSegment =
        RouteSegment(
            startIndex = getValue("startIndex").jsonPrimitive.int,
            endIndex = getValue("endIndex").jsonPrimitive.int,
            distanceMeters = getValue("distanceMeters").jsonPrimitive.double,
            durationSeconds = getValue("durationSeconds").jsonPrimitive.double,
        )

    private fun JsonArray.toPoint(): GeoPoint {
        val values = map { (it as JsonPrimitive).double }
        return GeoPoint(latitude = values[0], longitude = values[1])
    }
}

/** One captured answer and the stop order that produced it. */
internal data class ViaCase(
    val id: String,
    val profile: RouteProfile,
    val viaOrder: List<Int>,
    val route: Route,
) {
    /** The waypoints this case answers: start, its stops, then the destination. */
    val waypoints: List<GeoPoint>
        get() = listOf(ViaVectors.start) + viaOrder.map { index -> ViaVectors.vias[index] } + ViaVectors.destination
}
