package nl.r1a.grassmap.plugin.api.host

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.RouteHistoryEntry

/**
 * The reuse history behind the route planner (T-0304).
 *
 * The planner plugin owns the planning state; the host owns the file, the
 * privacy switch and the clock that the store writes with, so the plugin never
 * names a path. This mirrors `SearchHistoryStore`'s seam: a feature asks the
 * host, and `app` wires the persisted store.
 *
 * A build with no store wired answers [AppError.NotReady]; the planner treats
 * that as "no history" and keeps planning.
 */
interface RouteHistoryAccess {
    /**
     * Records [entry] as the newest used route and returns the whole list,
     * newest first. Recording the same chain again moves it to the front with
     * the entry's new time.
     */
    suspend fun remember(entry: RouteHistoryEntry): Outcome<List<RouteHistoryEntry>>

    /** The stored routes, newest first; empty when the privacy switch is off. */
    suspend fun recent(): Outcome<List<RouteHistoryEntry>>

    /** Removes every stored route, including with the switch off. */
    suspend fun clear(): Outcome<Unit>
}

/** The access a build with no route history wired gets. */
object NoRouteHistoryAccess : RouteHistoryAccess {
    override suspend fun remember(entry: RouteHistoryEntry): Outcome<List<RouteHistoryEntry>> = notReady()

    override suspend fun recent(): Outcome<List<RouteHistoryEntry>> = notReady()

    override suspend fun clear(): Outcome<Unit> = notReady()

    private fun notReady(): Outcome.Failure = Outcome.Failure(AppError.NotReady)
}
