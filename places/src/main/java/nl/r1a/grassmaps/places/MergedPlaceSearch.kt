package nl.r1a.grassmaps.places

import kotlinx.coroutines.delay
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.provider.PlaceHit

/**
 * One search over the offline indexes and, when the user allowed it, the online
 * provider (T-0212, T-0633).
 *
 * Ordering is a contract: offline rows keep the order the offline merge
 * produced and online rows follow them. An online row that repeats an earlier
 * row is dropped. "Repeats" means the same normalized name within
 * [DUPLICATE_RADIUS_METERS]; the first row wins, so the offline record and its
 * details stay the one the screen opens.
 *
 * When an online half will follow, the offline rows are published through
 * [SearchScreenRequest.onPartial] as soon as the offline half answers, so the
 * debounce never gates the first paint. The online provider then waits
 * [debounceMillis], which coalesces a burst of keystrokes into one request.
 * With online search off there is no second half, so the one ordinary result
 * is returned without an extra publish. Cancellation is checked across the
 * wait, and the provider's own gate still decides whether any socket may open.
 *
 * A failed online search never hides offline results. A failed offline search
 * still succeeds with the online rows when the online call succeeded, so a
 * device without a region index can use online search.
 *
 * When the privacy switch stops an online request that had already started,
 * the typed [AppError.SwitchOff] is reported through
 * [SearchScreenRequest.onOnlineStopped]. The offline rows still succeed, and
 * the note lets the screen say online search was turned off without calling an
 * offline row online (T-0616).
 */
class MergedPlaceSearch(
    private val offline: suspend (SearchScreenRequest) -> Outcome<List<PlaceHit>>,
    private val online: suspend (SearchScreenRequest) -> Outcome<List<PlaceHit>>,
    private val onlineEnabled: () -> Boolean,
    private val positionAllowed: () -> Boolean,
    private val debounceMillis: Long = ONLINE_DEBOUNCE_MILLIS,
    private val pause: suspend (Long) -> Unit = { millis -> delay(millis) },
) {
    suspend fun search(request: SearchScreenRequest): Outcome<List<PlaceHit>> {
        val offlineOutcome = offline(request)
        if (offlineOutcome is Outcome.Failure && offlineOutcome.error == AppError.Cancelled) {
            return offlineOutcome
        }
        val offlineHits = (offlineOutcome as? Outcome.Success)?.value.orEmpty()
        if (offlineHits.any { hit -> hit.isCoordinate }) {
            return offlineOutcome
        }
        if (!onlineEnabled()) {
            return offlineOutcome
        }
        if (offlineOutcome is Outcome.Success) {
            request.onPartial(offlineHits)
        }
        if (request.cancellation.isCancelled()) {
            return Outcome.Failure(AppError.Cancelled)
        }
        if (debounceMillis > 0) {
            pause(debounceMillis)
        }
        if (request.cancellation.isCancelled()) {
            return Outcome.Failure(AppError.Cancelled)
        }
        val onlineRequest =
            request.copy(
                context =
                    request.context.copy(
                        position = request.context.position.takeIf { positionAllowed() },
                    ),
            )
        val onlineOutcome = online(onlineRequest)
        if (onlineOutcome is Outcome.Failure && onlineOutcome.error == AppError.Cancelled) {
            return onlineOutcome
        }
        val stopped = (onlineOutcome as? Outcome.Failure)?.error as? AppError.SwitchOff
        if (stopped != null) {
            request.onOnlineStopped(stopped)
        }
        if (request.cancellation.isCancelled()) {
            return Outcome.Failure(AppError.Cancelled)
        }
        val onlineHits = (onlineOutcome as? Outcome.Success)?.value.orEmpty()
        val merged = mergeHits(offlineHits, onlineHits)
        return when {
            offlineOutcome is Outcome.Success -> Outcome.Success(merged)
            onlineOutcome is Outcome.Success -> Outcome.Success(merged)
            else -> offlineOutcome
        }
    }

    companion object {
        /**
         * A pause longer than a fast typist's inter-key interval, short enough
         * that a finished query does not feel delayed. One request follows a
         * burst instead of one per character.
         */
        const val ONLINE_DEBOUNCE_MILLIS: Long = 350

        /**
         * The same OSM feature can be a node in the offline index and a way
         * centroid in Photon; tens of metres apart is still one place. 75 m
         * keeps those together without merging neighbouring same-name branches.
         */
        const val DUPLICATE_RADIUS_METERS: Double = 75.0

        /**
         * The merge rule, exposed for the test and for callers that need to
         * explain it: offline order first, online rows appended unless an
         * earlier row has the same normalized name and lies within the radius.
         */
        fun mergeHits(
            offline: List<PlaceHit>,
            online: List<PlaceHit>,
        ): List<PlaceHit> {
            val accepted = offline.toMutableList()
            for (candidate in online) {
                if (accepted.none { existing -> isDuplicate(existing, candidate) }) {
                    accepted += candidate
                }
            }
            return accepted
        }

        /** True when [candidate] repeats an earlier row by name and proximity. */
        fun isDuplicate(
            existing: PlaceHit,
            candidate: PlaceHit,
        ): Boolean {
            val name = PlaceMatching.normalizedName(existing.name)
            if (name.isEmpty() || name != PlaceMatching.normalizedName(candidate.name)) {
                return false
            }
            return PlaceMatching.distanceMeters(existing.position, candidate.position) <= DUPLICATE_RADIUS_METERS
        }
    }
}
