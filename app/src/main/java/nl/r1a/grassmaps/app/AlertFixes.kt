package nl.r1a.grassmaps.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import nl.r1a.grassmaps.navigation.NavigationPhase
import nl.r1a.grassmaps.navigation.NavigationState
import nl.r1a.grassmaps.plugin.api.model.LocationFix

/**
 * The fixes one live trip produces, as the alert host reads them (T-0317).
 *
 * The location source is shared and starts only while it has a collector. The
 * alert host lives for the process, so a collector on the raw source would
 * hold location after the trip, when neither the map nor guidance needs it. A
 * fix only decides an alert while guidance runs, so the source is read for
 * [NavigationPhase.Following], [NavigationPhase.Rerouting] and
 * [NavigationPhase.Paused] and released at every other phase. A paused trip
 * keeps its subscription: the session drops its fixes but the service and
 * location keep running, and the next fix stays a fresh baseline for the
 * retrospective gap.
 *
 * A phase change re-subscribes to [fixes], and the shared source replays its
 * last fix. The host treats a repeated timestamp as a new baseline, so the
 * replay announces nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun alertFixes(
    states: Flow<NavigationState>,
    fixes: Flow<LocationFix?>,
): Flow<LocationFix> =
    states
        .map { state -> state.phase }
        .distinctUntilChanged()
        .flatMapLatest { phase ->
            if (phase.isGuidanceActive()) {
                fixes.filterNotNull()
            } else {
                emptyFlow()
            }
        }

/**
 * Whether guidance is running or paused, so a fix can still decide an alert
 * or the live no-fix relation. [NavigationPhase.Arrived] and
 * [NavigationPhase.Stopped] are finished, and [NavigationPhase.Idle] has no
 * trip.
 */
internal fun NavigationPhase.isGuidanceActive(): Boolean =
    this == NavigationPhase.Following || this == NavigationPhase.Rerouting || this == NavigationPhase.Paused
