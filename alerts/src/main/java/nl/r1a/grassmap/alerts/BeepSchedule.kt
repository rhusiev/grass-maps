package nl.r1a.grassmap.alerts

import nl.r1a.grassmap.navigation.NavigationInstruction
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The distances at which a beep sounds before a maneuver (T-0318).
 *
 * Each distance is about ten to eleven seconds of travel at the profile's
 * nominal speed: 15 m walking at 5 km/h, 50 m cycling at 18 km/h and 150 m
 * driving at 50 km/h. A single value cannot serve all three: 100 m is 72 s of
 * walking and seven seconds of driving.
 */
object BeepDistance {
    const val WALK_METERS: Double = 15.0
    const val BIKE_METERS: Double = 50.0
    const val CAR_METERS: Double = 150.0

    /** The beep distance for [profile]; hiking uses the walking distance. */
    fun forProfile(profile: RouteProfile): Double =
        when (profile) {
            RouteProfile.Walk, RouteProfile.Hike -> WALK_METERS
            RouteProfile.Bike -> BIKE_METERS
            RouteProfile.Car -> CAR_METERS
        }
}

/**
 * The identity of one maneuver inside one route revision. The route revision
 * re-arms every turn after a reroute; the instruction index separates two
 * turns that carry the same text.
 */
data class BeepTurnKey(
    val routeRevision: Long,
    /** The position in `activeRoute.instructions`, or [FALLBACK_INDEX]. */
    val instructionIndex: Int,
    /** The text-based identity used when the route carries no matching instruction. */
    val fallback: String? = null,
) {
    companion object {
        const val FALLBACK_INDEX: Int = -1
    }
}

/** One turn the schedule decided to sound, with the distance that decided it. */
data class BeepCue(
    val instruction: String,
    val streetName: String?,
    val distanceMeters: Double,
    val routeRevision: Long,
    val turnKey: BeepTurnKey,
)

/**
 * The beep policy for one navigation state stream (T-0318).
 *
 * A turn is **armed** when the next instruction is seen farther than the
 * distance for the active profile, and it beeps on the first later state that
 * reports the same turn at or inside that distance. A turn that is first seen
 * inside the distance is not armed, so attaching to a session mid-approach
 * does not beep late. The key is [BeepTurnKey]: the route revision plus the
 * index of the matching `RouteInstruction`, so a reroute re-arms its new turns
 * and two turns with the same text do not collapse into one. When the active
 * route has no matching instruction the key falls back to the instruction text
 * and street plus a ten-metre bucket of the maneuver's route position.
 *
 * A turn is beeped at most once per route revision. A fix gap of at least
 * [AlertHost.SIGNAL_GAP_MILLIS] disarms the current turn, and a phase that is
 * not guidance (paused, arrived, stopped, idle) disarms it too, so a jump past
 * the distance never produces a late beep. The schedule holds no clock and no
 * Android type; [BeepHost] feeds it and a JVM test drives it with states.
 */
class BeepSchedule(
    initialProfile: RouteProfile = RouteProfile.Walk,
    initialThresholdMeters: Double = BeepDistance.forProfile(initialProfile),
) {
    init {
        require(initialThresholdMeters.isFinite() && initialThresholdMeters > 0.0) {
            "the beep distance must be finite and positive"
        }
    }

    /** The profile whose beep distance is in use. */
    var profile: RouteProfile = initialProfile
        private set

    /** The current beep distance, in metres. */
    var thresholdMeters: Double = initialThresholdMeters
        private set

    private var armed: BeepTurnKey? = null
    private var beeped: BeepTurnKey? = null
    private var lastFixTimeMillis: Long? = null

    /** Changes the profile and its beep distance; the next state re-arms. */
    @Synchronized
    fun setProfile(profile: RouteProfile) {
        this.profile = profile
        thresholdMeters = BeepDistance.forProfile(profile)
        reset()
    }

    /** Forgets the current approach and the beeped turn, for a new session. */
    @Synchronized
    fun reset() {
        armed = null
        beeped = null
        lastFixTimeMillis = null
    }

    /** Reads one navigation state and returns a beep when the turn is due. */
    @Synchronized
    fun onState(state: NavigationState): BeepCue? {
        when (state.phase) {
            NavigationPhase.Idle, NavigationPhase.Arrived, NavigationPhase.Stopped -> {
                reset()
                return null
            }

            NavigationPhase.Paused -> {
                armed = null
                return null
            }

            NavigationPhase.Following, NavigationPhase.Rerouting -> {
                Unit
            }
        }

        val fixTimeMillis = state.fix?.timeMillis
        if (fixTimeMillis != null) {
            val previous = lastFixTimeMillis
            lastFixTimeMillis = fixTimeMillis
            if (previous != null && fixTimeMillis > previous && fixTimeMillis - previous >= AlertHost.SIGNAL_GAP_MILLIS) {
                armed = null
            }
        }

        val instruction = state.nextInstruction
        val distance = instruction?.distanceToInstructionMeters
        if (instruction == null || distance == null || !distance.isFinite()) {
            armed = null
            return null
        }

        val key = keyOf(state, instruction)
        if (key == beeped) {
            return null
        }
        if (distance > thresholdMeters) {
            armed = key
            return null
        }
        if (distance < 0.0 || armed != key) {
            armed = null
            return null
        }
        armed = null
        beeped = key
        return BeepCue(
            instruction = instruction.text,
            streetName = instruction.streetName,
            distanceMeters = distance,
            routeRevision = state.routeRevision,
            turnKey = key,
        )
    }

    private fun keyOf(
        state: NavigationState,
        instruction: NavigationInstruction,
    ): BeepTurnKey {
        val index = matchingInstructionIndex(state, instruction)
        return if (index >= 0) {
            BeepTurnKey(state.routeRevision, index)
        } else {
            BeepTurnKey(
                routeRevision = state.routeRevision,
                instructionIndex = BeepTurnKey.FALLBACK_INDEX,
                fallback = fallbackKey(state, instruction),
            )
        }
    }

    /**
     * Finds the route instruction the next instruction describes. The
     * maneuver's absolute route position is the distance already travelled plus
     * the distance ahead, and the nearest route instruction within the match
     * tolerance is the turn. A follower-supplied text that does not match the
     * route's own list falls back to the text key.
     */
    private fun matchingInstructionIndex(
        state: NavigationState,
        instruction: NavigationInstruction,
    ): Int {
        val route = state.activeRoute ?: return -1
        if (route.instructions.isEmpty() || !state.remainingDistanceMeters.isFinite()) {
            return -1
        }
        val distanceAhead = instruction.distanceToInstructionMeters ?: return -1
        val along = route.distanceMeters - state.remainingDistanceMeters
        val expected = along + distanceAhead
        if (!expected.isFinite()) {
            return -1
        }
        var bestIndex = -1
        var bestDistance = Double.MAX_VALUE
        route.instructions.forEachIndexed { index, candidate ->
            val difference = abs(candidate.distanceAlongRouteMeters - expected)
            if (difference < bestDistance) {
                bestDistance = difference
                bestIndex = index
            }
        }
        return if (bestDistance <= INSTRUCTION_MATCH_TOLERANCE_METERS) bestIndex else -1
    }

    private fun fallbackKey(
        state: NavigationState,
        instruction: NavigationInstruction,
    ): String {
        val route = state.activeRoute
        val along =
            if (route != null && state.remainingDistanceMeters.isFinite()) {
                route.distanceMeters - state.remainingDistanceMeters
            } else {
                0.0
            }
        val position = along + (instruction.distanceToInstructionMeters ?: 0.0)
        val bucket = (position / FALLBACK_POSITION_BUCKET_METERS).roundToLong()
        return "${instruction.text}|${instruction.streetName.orEmpty()}|$bucket"
    }

    private companion object {
        const val INSTRUCTION_MATCH_TOLERANCE_METERS = 10.0
        const val FALLBACK_POSITION_BUCKET_METERS = 10.0
    }
}
