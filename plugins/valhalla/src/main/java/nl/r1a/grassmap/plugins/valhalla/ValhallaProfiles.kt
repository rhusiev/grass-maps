package nl.r1a.grassmap.plugins.valhalla

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import nl.r1a.grassmap.plugin.api.provider.RouteProfile

/**
 * The Valhalla costing settings behind each v1 profile.
 *
 * The same document is committed at `test-data/routing/valhalla-profiles.json`
 * and is what the validation run sends, so the measurement cannot drift from
 * the app: `ValhallaProfilesTest` fails when the two disagree. Every value is a
 * Valhalla 3.9 option with the meaning `test-data/routing/README.md` records.
 */
internal object ValhallaProfiles {
    private const val WALKING_SPEED_KMH = 5.0
    private const val HIKING_SPEED_KMH = 4.5
    private const val CYCLING_SPEED_KMH = 18.0

    /** Stairs are usable for both foot profiles, at a per-flight time cost. */
    private const val CITY_STEP_PENALTY_S = 30.0
    private const val TRAIL_STEP_PENALTY_S = 20.0

    /** T1 hiking: a walker does not take a marked mountain trail. */
    private const val WALK_MAX_HIKING_DIFFICULTY = 1

    /** T5: demanding alpine hiking, one step below trails that need climbing gear. */
    private const val HIKE_MAX_HIKING_DIFFICULTY = 5

    val WALK: ValhallaProfile =
        ValhallaProfile(
            costing = PEDESTRIAN,
            costingOptions =
                pedestrianOptions {
                    put("walking_speed", WALKING_SPEED_KMH)
                    put("step_penalty", CITY_STEP_PENALTY_S)
                    put("max_hiking_difficulty", WALK_MAX_HIKING_DIFFICULTY)
                },
        )

    val HIKE: ValhallaProfile =
        ValhallaProfile(
            costing = PEDESTRIAN,
            costingOptions =
                pedestrianOptions {
                    put("walking_speed", HIKING_SPEED_KMH)
                    put("step_penalty", TRAIL_STEP_PENALTY_S)
                    put("max_hiking_difficulty", HIKE_MAX_HIKING_DIFFICULTY)
                    // A hiker accepts the climb the route needs.
                    put("use_hills", 0.9)
                },
        )

    val BIKE: ValhallaProfile =
        ValhallaProfile(
            costing = BICYCLE,
            costingOptions =
                buildJsonObject {
                    // A general-purpose city bike: the 18 km/h hybrid.
                    put("bicycle_type", "hybrid")
                    put("cycling_speed", CYCLING_SPEED_KMH)
                    // A hybrid takes a usable street instead of detouring
                    // (0.3 against the 0.25 default), accepts a climb to stay
                    // off the busy line (0.4), and stays off broken surfaces
                    // (0.5 against the 0.25 default).
                    put("use_roads", 0.3)
                    put("use_hills", 0.4)
                    put("avoid_bad_surfaces", 0.5)
                    // A bike still crosses by ferry, at a visible cost.
                    put("use_ferry", 0.5)
                },
        )

    /** Car is out of v1, so it carries no tuned options. */
    private val CAR = ValhallaProfile(costing = AUTO, costingOptions = JsonObject(emptyMap()))

    fun of(profile: RouteProfile): ValhallaProfile =
        when (profile) {
            RouteProfile.Walk -> WALK
            RouteProfile.Bike -> BIKE
            RouteProfile.Hike -> HIKE
            RouteProfile.Car -> CAR
        }

    /** The committed document, compared verbatim by the profile test. */
    fun document(): JsonObject =
        buildJsonObject {
            put("version", 1)
            putJsonObject("profiles") {
                put("walk", WALK.json())
                put("bike", BIKE.json())
                put("hike", HIKE.json())
            }
        }

    private fun pedestrianOptions(block: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            // Ferries are allowed; a pedestrian or hiker can board one.
            put("use_ferry", 1.0)
            // Paths are the goal on foot, sidewalks are preferred in town, and
            // service ways are avoided. These are Valhalla's foot defaults,
            // written down so a change is a decision rather than a version bump.
            put("walkway_factor", 1.0)
            put("sidewalk_factor", 0.9)
            put("alley_factor", 2.0)
            put("driveway_factor", 5.0)
            put("use_hills", 0.5)
            block()
        }

    private fun ValhallaProfile.json(): JsonObject =
        buildJsonObject {
            put("costing", costing)
            putJsonObject("costing_options") {
                put(costing, costingOptions)
            }
        }

    const val PEDESTRIAN = "pedestrian"
    const val BICYCLE = "bicycle"
    const val AUTO = "auto"
}

/** One profile as Valhalla receives it. */
internal data class ValhallaProfile(
    val costing: String,
    val costingOptions: JsonObject,
)
