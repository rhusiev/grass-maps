package nl.r1a.grassmap.core.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import nl.r1a.grassmap.plugin.api.model.LocationFix

/**
 * One provider collection shared by every consumer.
 *
 * Two consumers need fixes at once - the map's position collector and an active
 * navigation session - and location must still be released when neither needs
 * it. Sharing one upstream collection does both: the platform registers its
 * providers once, and the registration stops as soon as the last subscriber
 * leaves. That is the mechanism behind the release rule: with no navigation,
 * the activity owns the only subscription and stops it when the app leaves the
 * foreground; during navigation the service owns a subscription for as long as
 * guidance runs.
 *
 * The last fix is replayed to a new subscriber, so a navigation session that
 * starts while the map already has a position begins from that position.
 */
class SharedLocationSource(
    private val source: LocationSource,
    scope: CoroutineScope,
) : LocationSource {
    private val sharedFixes: SharedFlow<LocationFix?> =
        source.fixes.shareIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(),
            replay = LAST_FIX_REPLAY,
        )

    override val fixes: Flow<LocationFix?> get() = sharedFixes

    override fun hasPermission(): Boolean = source.hasPermission()

    override fun requiresPermission(): Boolean = source.requiresPermission()

    private companion object {
        const val LAST_FIX_REPLAY = 1
    }
}
