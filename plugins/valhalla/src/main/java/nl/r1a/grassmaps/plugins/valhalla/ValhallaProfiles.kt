package nl.r1a.grassmaps.plugins.valhalla

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import nl.r1a.grassmaps.plugin.api.provider.RouteAttribute
import nl.r1a.grassmaps.plugin.api.provider.RouteOptions
import nl.r1a.grassmaps.plugin.api.provider.RoutePreference
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile

/**
 * The Valhalla costing settings behind each v1 profile.
 *
 * The same document is committed at `test-data/routing/valhalla-profiles.json`
 * and is what the validation run sends, so the measurement cannot drift from
 * the app: `ValhallaProfilesTest` fails when the two disagree. Every value is a
 * Valhalla 3.9 option with the meaning `test-data/routing/README.md` records.
 */
internal object ValhallaProfiles {
    private const val PEDESTRIAN_MAX_DISTANCE_METERS = 250_000.0
    private const val BICYCLE_MAX_DISTANCE_METERS = 500_000.0
    private const val AUTO_MAX_DISTANCE_METERS = 5_000_000.0

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

    /**
     * The longest route Valhalla plans for [profile]: the `max_distance` of the
     * profile's costing in `service_limits`. The numbers are the defaults of
     * `valhalla_build_config`, which writes every pack's `valhalla.json`
     * (`tools/pipeline/build-valhalla-pack.sh`), and match the pack the
     * T-0518 build produced. The pack's own value is not read at run time.
     */
    fun maxDistanceMeters(profile: RouteProfile): Double =
        when (profile) {
            RouteProfile.Walk, RouteProfile.Hike -> PEDESTRIAN_MAX_DISTANCE_METERS
            RouteProfile.Bike -> BICYCLE_MAX_DISTANCE_METERS
            RouteProfile.Car -> AUTO_MAX_DISTANCE_METERS
        }

    fun of(profile: RouteProfile): ValhallaProfile =
        when (profile) {
            RouteProfile.Walk -> WALK
            RouteProfile.Bike -> BIKE
            RouteProfile.Hike -> HIKE
            RouteProfile.Car -> CAR
        }

    /**
     * The costing options for one request: the tuned profile document with the
     * profile's own attributes applied when a step is not
     * [RoutePreference.Normal] (T-0421).
     *
     * Only the attributes in [RouteAttribute.forProfile] are read, so a stored
     * value for an attribute the profile does not show never reaches the body.
     * `Normal` keeps the profile's tuned value. The steps are a cost
     * preference, not an access rule, with one exception: `max_hiking_difficulty`
     * filters out trails above the limit. The mapping, with the per-key effect
     * in `test-data/routing/README.md`:
     * - hills (all three): `use_hills` 0.0 / tuned / 1.0
     * - steps (walk, hike): `step_penalty` [STEP_PENALTY_AVOID_S] / tuned / 0
     * - trail difficulty (hike): `max_hiking_difficulty`
     *   [TRAIL_DIFFICULTY_AVOID] / tuned / [MAX_HIKING_DIFFICULTY]
     * - busy roads (bike): `use_roads` 0.0 / tuned / 1.0
     * - rough surfaces (bike): `avoid_bad_surfaces` 1.0 / tuned / 0.0
     *
     * Car is out of v1 and keeps its profile.
     */
    fun costingOptions(
        profile: RouteProfile,
        options: RouteOptions,
    ): JsonObject {
        val base = of(profile).costingOptions
        val overrides = mutableMapOf<String, JsonPrimitive>()
        for (attribute in RouteAttribute.forProfile(profile)) {
            val mapping = mappingFor(profile, attribute) ?: continue
            when (options[attribute]) {
                RoutePreference.Avoid -> overrides[mapping.key] = mapping.avoid
                RoutePreference.Normal -> Unit
                RoutePreference.DontMind -> overrides[mapping.key] = mapping.dontMind
            }
        }
        if (overrides.isEmpty()) {
            return base
        }
        return JsonObject(base + overrides)
    }

    /** One attribute's Valhalla key and the values of its two non-normal steps. */
    private class StepMapping(
        val key: String,
        val avoid: JsonPrimitive,
        val dontMind: JsonPrimitive,
    )

    private fun mappingFor(
        profile: RouteProfile,
        attribute: RouteAttribute,
    ): StepMapping? =
        when (attribute) {
            RouteAttribute.Hills -> {
                StepMapping(USE_HILLS_KEY, JsonPrimitive(0.0), JsonPrimitive(1.0))
            }

            RouteAttribute.Steps -> {
                StepMapping(STEP_PENALTY_KEY, JsonPrimitive(STEP_PENALTY_AVOID_S), JsonPrimitive(0.0))
            }

            RouteAttribute.TrailDifficulty -> {
                StepMapping(
                    MAX_HIKING_DIFFICULTY_KEY,
                    JsonPrimitive(TRAIL_DIFFICULTY_AVOID),
                    JsonPrimitive(MAX_HIKING_DIFFICULTY),
                )
            }

            RouteAttribute.BusyRoads -> {
                StepMapping(USE_ROADS_KEY, JsonPrimitive(0.0), JsonPrimitive(1.0)).takeIf { profile == RouteProfile.Bike }
            }

            RouteAttribute.RoughSurfaces -> {
                StepMapping(AVOID_BAD_SURFACES_KEY, JsonPrimitive(1.0), JsonPrimitive(0.0))
                    .takeIf { profile == RouteProfile.Bike }
            }
        }

    private const val USE_HILLS_KEY = "use_hills"
    private const val USE_ROADS_KEY = "use_roads"
    private const val AVOID_BAD_SURFACES_KEY = "avoid_bad_surfaces"
    private const val STEP_PENALTY_KEY = "step_penalty"
    private const val MAX_HIKING_DIFFICULTY_KEY = "max_hiking_difficulty"

    /** Valhalla's top of the `sac_scale` range: every trail grade is allowed. */
    private const val MAX_HIKING_DIFFICULTY = 6

    /** T2, easy mountain hiking: the lowest grade above plain footpaths. */
    private const val TRAIL_DIFFICULTY_AVOID = 2

    /** Eight minutes per step transition: the strongest avoidance the engine honours. */
    private const val STEP_PENALTY_AVOID_S = 480.0

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
            // Paths are the goal on foot and service ways are avoided; these
            // are Valhalla's foot defaults, written down so a change is a
            // decision rather than a version bump. The one deviation is
            // sidewalk_factor 0.9 against the 1.0 default: in town a walker
            // should stay on the sidewalk beside the road, not in the road.
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
