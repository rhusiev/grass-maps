package nl.r1a.grassmap.plugin.api.service

import kotlinx.coroutines.flow.Flow
import nl.r1a.grassmap.plugin.api.model.LocationFix

/**
 * The platform location source (architecture.md section 4.3).
 *
 * The flow is cold: it starts provider updates for its collector and stops them
 * when the collector goes away. A null emission means there is no position to
 * show yet; the first value may be a stale last-known fix while the source
 * waits for a live one. The service stays foreground-only: it never asks for a
 * background location permission.
 *
 * Permission is granted by the user through the activity, never by this
 * contract. [requiresPermission] tells a caller whether it must ask first.
 */
interface LocationService {
    /** Position fixes, newest last. Null when there is no fix to show. */
    val fixes: Flow<LocationFix?>

    /** Whether the platform grants this app a foreground location permission. */
    fun hasPermission(): Boolean

    /**
     * Whether the source needs a runtime location permission. A replay or an
     * uninstalled source reports false, so callers never ask the user for a
     * permission it cannot use.
     */
    fun requiresPermission(): Boolean = true
}
