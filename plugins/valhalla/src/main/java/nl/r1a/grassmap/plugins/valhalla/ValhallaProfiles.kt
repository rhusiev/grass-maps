package nl.r1a.grassmap.plugins.valhalla

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
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

    /**
     * The costing options for one request: the tuned profile document with the
     * three preferences applied when a step is not [RoutePreference.Normal]
     * (T-0421).
     *
     * Valhalla reads `use_hills`, `use_roads`, `use_living_streets` and
     * `use_tracks` as willingness, 0 to 1, and `avoid_bad_surfaces` as
     * avoidance, 0 to 1. `Normal` keeps the profile's tuned value or, when the
     * profile does not name the key, Valhalla's own default. The steps are a
     * cost preference, not an access rule: an avoided way is still used when no
     * other route exists.
     *
     * The mapping is:
     * - hills: `use_hills` 0.0 / tuned / 1.0 for walk, hike and bike
     * - busy roads: bike `use_roads` 0.0 / tuned / 1.0; walk and hike
     *   `use_living_streets` 1.0 / tuned / 0.0
     * - rough surfaces: bike `avoid_bad_surfaces` 1.0 / tuned / 0.0; walk and
     *   hike `use_tracks` 0.0 / tuned / 1.0
     *
     * Pedestrian costing has no `use_roads` or `avoid_bad_surfaces`, so the on
     * foot steps use the closest pedestrian option instead of a key Valhalla
     * ignores. Car is out of v1 and keeps its profile.
     *
     * The setting is applied only to the profile in the request, so another
     * profile's options cannot appear in this body.
     */
    fun costingOptions(
        profile: RouteProfile,
        options: RouteOptions,
    ): JsonObject {
        val base = of(profile).costingOptions
        val overrides = mutableMapOf<String, JsonPrimitive>()
        when (profile) {
            RouteProfile.Walk, RouteProfile.Hike -> {
                applyPreference(overrides, USE_HILLS_KEY, options.hills, avoid = 0.0, dontMind = 1.0)
                applyPreference(overrides, USE_LIVING_STREETS_KEY, options.busyRoads, avoid = 1.0, dontMind = 0.0)
                applyPreference(overrides, USE_TRACKS_KEY, options.roughSurfaces, avoid = 0.0, dontMind = 1.0)
            }

            RouteProfile.Bike -> {
                applyPreference(overrides, USE_HILLS_KEY, options.hills, avoid = 0.0, dontMind = 1.0)
                applyPreference(overrides, USE_ROADS_KEY, options.busyRoads, avoid = 0.0, dontMind = 1.0)
                applyPreference(overrides, AVOID_BAD_SURFACES_KEY, options.roughSurfaces, avoid = 1.0, dontMind = 0.0)
            }

            RouteProfile.Car -> {
                Unit
            }
        }
        if (overrides.isEmpty()) {
            return base
        }
        return JsonObject(base + overrides)
    }

    /**
     * Applies one step to one key. [avoid] and [dontMind] are the values in the
     * direction the key reads, which is not the same direction for every key.
     */
    private fun applyPreference(
        overrides: MutableMap<String, JsonPrimitive>,
        key: String,
        preference: RoutePreference,
        avoid: Double,
        dontMind: Double,
    ) {
        when (preference) {
            RoutePreference.Avoid -> overrides[key] = JsonPrimitive(avoid)
            RoutePreference.Normal -> Unit
            RoutePreference.DontMind -> overrides[key] = JsonPrimitive(dontMind)
        }
    }

    private const val USE_HILLS_KEY = "use_hills"
    private const val USE_ROADS_KEY = "use_roads"
    private const val USE_LIVING_STREETS_KEY = "use_living_streets"
    private const val USE_TRACKS_KEY = "use_tracks"
    private const val AVOID_BAD_SURFACES_KEY = "avoid_bad_surfaces"

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
