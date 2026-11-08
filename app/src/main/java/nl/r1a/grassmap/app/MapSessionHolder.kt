package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.Camera

/**
 * Gives process-scoped commands and plugins the map host of the current
 * Activity.
 *
 * The host is bound while a map surface is composed and cleared when that same
 * instance is disposed, so a command never writes to a dead engine. Commands
 * return [nl.r1a.grassmap.plugin.api.error.AppError.NotReady] before the first
 * bind and after the surface goes away.
 *
 * [camera] keeps the last camera the bound host published, so a plugin that
 * needs a map centre still has one after the surface goes away and gets the
 * new surface's camera as soon as it binds.
 */
class MapSessionHolder {
    @Volatile
    private var host: MapHost? = null

    private val latestCamera = MutableStateFlow<Camera?>(null)

    /** The camera of the last bound host; empty until a map surface binds. */
    val camera: Flow<Camera> = latestCamera.filterNotNull()

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

    /** Records the camera a bound host published. */
    fun recordCamera(camera: Camera) {
        latestCamera.value = camera
    }

    fun hostOrNull(): MapHost? = host
}
