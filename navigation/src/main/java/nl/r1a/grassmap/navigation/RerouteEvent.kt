package nl.r1a.grassmap.navigation

/**
 * One reroute the session installed, with the fix indices that produced it.
 *
 * [episodeStartFix] is the first fix of the off-route streak that led here.
 * [budgetFix] is the fix whose travel spent the profile's off-route budget and
 * started the request; [installedFix] is the fix being processed when the
 * replacement route was installed. The request is awaited inside one fix, so
 * [budgetFix] and [installedFix] are the same index by construction;
 * [durationMillis] is the session's `reroute_ms`.
 *
 * The session reports every event it installs. A stream that stays off the new
 * line starts a new episode at the fix after the reroute, so several events in
 * one stream are separate episodes, not a re-fire of the first.
 */
data class RerouteEvent(
    val index: Int,
    val episodeStartFix: Int,
    val budgetFix: Int,
    val installedFix: Int,
    val budgetTravelMeters: Double,
    val durationMillis: Long,
    val replacementDistanceMeters: Double,
    val replacementPoints: Int,
)
