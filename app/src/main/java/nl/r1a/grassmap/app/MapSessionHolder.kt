package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.Camera
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Gives process-scoped commands and plugins the map host of the current
 * Activity.
 *
 * A host is bound while a map surface is composed and removed when that same
 * instance is disposed, so a command never writes to a dead engine. Two
 * Activity instances can be alive at once - a link opened from another app
 * starts a second one - and each composes its own surface, so the holder keeps
 * every bound host and reports the newest. When the newer instance finishes,
 * its unbind leaves the older host in place and commands keep working instead
 * of failing with [nl.r1a.grassmap.plugin.api.error.AppError.NotReady].
 *
 * Commands return NotReady only before the first bind and after the last
 * unbind.
 *
 * [camera] keeps the last camera a bound host published, so a plugin that
 * needs a map centre still has one after the surface goes away and gets the
 * new surface's camera as soon as it binds.
 */
class MapSessionHolder {
    private val hosts = CopyOnWriteArrayList<MapHost>()

    private val latestCamera = MutableStateFlow<Camera?>(null)

    /** The camera of the last bound host; empty until a map surface binds. */
    val camera: Flow<Camera> = latestCamera.filterNotNull()

    /** Adds [host]; binding the same instance twice keeps one entry. */
    fun bind(host: MapHost) {
        if (hosts.none { it === host }) {
            hosts += host
        }
    }

    /** Removes only [host]; a host bound by another surface stays current. */
    fun unbind(host: MapHost) {
        hosts.removeAll { it === host }
    }

    /** Records the camera a bound host published. */
    fun recordCamera(camera: Camera) {
        latestCamera.value = camera
    }

    /** The newest bound host, or null when no surface is composed. */
    fun hostOrNull(): MapHost? = hosts.lastOrNull()
}
