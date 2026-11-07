package nl.r1a.grassmap.core.services

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.service.LocationService

/**
 * The location source the app consumes. It is the plugin-api contract, so a
 * feature never needs a core-services type.
 */
typealias LocationSource = LocationService

/** The source a build uses when it has no location provider at all. */
object NoLocationSource : LocationSource {
    override val fixes: Flow<LocationFix?> = flow { emit(null) }

    override fun hasPermission(): Boolean = false

    override fun requiresPermission(): Boolean = false
}
