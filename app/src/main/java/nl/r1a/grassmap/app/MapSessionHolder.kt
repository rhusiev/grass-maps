package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.host.MapHost

/**
 * Gives process-scoped commands the map host of the current Activity.
 *
 * The host is bound while a map surface is composed and cleared when that same
 * instance is disposed, so a command never writes to a dead engine. Commands
 * return [nl.r1a.grassmap.plugin.api.error.AppError.NotReady] before the first
 * bind and after the surface goes away.
 */
class MapSessionHolder {
    @Volatile
    private var host: MapHost? = null

    fun bind(host: MapHost) {
        this.host = host
    }

    /** Clears the binding only when [host] is still the bound instance. */
    @Synchronized
    fun unbind(host: MapHost) {
        if (this.host === host) {
            this.host = null
        }
    }

    fun hostOrNull(): MapHost? = host
}
