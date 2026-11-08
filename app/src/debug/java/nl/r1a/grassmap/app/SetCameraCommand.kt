package nl.r1a.grassmap.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.MapEvent
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.GeoPoint

/** The debug-only command behind the golden screenshots. */
internal val SET_CAMERA_COMMAND: CommandId = CommandId("core.map.set_camera")

/**
 * Moves the camera to one exact position and waits for the map to go idle, so a
 * screenshot taken after it returns shows a settled frame. It exists only in
 * the debug source set: the release app contributes no camera command.
 */
internal class SetCameraCommand(
    private val session: MapSessionHolder,
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MS,
) : Command<JsonElement, String>(
        id = SET_CAMERA_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Sets lat, lon, zoom, bearing and tilt, then waits for map idle.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val request = parse(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val host = session.hostOrNull() ?: return Outcome.Failure(AppError.NotReady)
        return coroutineScope {
            // A stale Idle from the previous position can complete before the
            // engine has applied this move; T-0108 caught one frame mid-flight
            // that way. Accept an Idle only after a CameraChanged carrying the
            // requested camera.
            val settled = CompletableDeferred<Unit>()
            val watcher =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    var reached = false
                    host.events.collect { event ->
                        when (event) {
                            is MapEvent.CameraChanged -> {
                                reached = reached || request.matches(event.camera)
                            }

                            MapEvent.Idle -> {
                                if (reached) {
                                    settled.complete(Unit)
                                }
                            }

                            else -> {
                                Unit
                            }
                        }
                    }
                }
            when (
                val moved =
                    host.moveCamera(
                        CameraUpdate(
                            center = GeoPoint(request.latitude, request.longitude),
                            zoom = request.zoom,
                            bearing = request.bearing,
                            tilt = request.tilt,
                        ),
                    )
            ) {
                is Outcome.Failure -> {
                    watcher.cancelAndJoin()
                    moved
                }

                is Outcome.Success -> {
                    val idle = withTimeoutOrNull(idleTimeoutMillis) { settled.await() } != null
                    watcher.cancelAndJoin()
                    Outcome.Success(CommandResult(request.summary(idle)))
                }
            }
        }
    }

    private fun parse(params: JsonElement): CameraRequest? {
        val json = params as? JsonObject ?: return null
        val latitude = json.double("lat") ?: return null
        val longitude = json.double("lon") ?: return null
        val zoom = json.double("zoom") ?: return null
        val bearing = json.double("bearing") ?: 0.0
        val tilt = json.double("tilt") ?: 0.0
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
        if (!zoom.isFinite() || zoom !in MIN_ZOOM..MAX_ZOOM) return null
        if (!bearing.isFinite() || bearing !in 0.0..MAX_BEARING) return null
        if (!tilt.isFinite() || tilt !in 0.0..MAX_TILT) return null
        return CameraRequest(latitude, longitude, zoom, bearing, tilt)
    }

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private data class CameraRequest(
        val latitude: Double,
        val longitude: Double,
        val zoom: Double,
        val bearing: Double,
        val tilt: Double,
    ) {
        fun matches(camera: nl.r1a.grassmap.plugin.api.model.Camera): Boolean =
            kotlin.math.abs(camera.center.latitude - latitude) < POSITION_EPSILON &&
                kotlin.math.abs(camera.center.longitude - longitude) < POSITION_EPSILON &&
                kotlin.math.abs(camera.zoom - zoom) < ZOOM_EPSILON &&
                kotlin.math.abs(camera.bearing - bearing) < ANGLE_EPSILON &&
                kotlin.math.abs(camera.tilt - tilt) < ANGLE_EPSILON

        fun summary(idle: Boolean): String = "lat=$latitude lon=$longitude zoom=$zoom bearing=$bearing tilt=$tilt idle=$idle"
    }

    private companion object {
        const val MIN_ZOOM = 1.0
        const val MAX_ZOOM = 20.0
        const val MAX_BEARING = 360.0
        const val MAX_TILT = 60.0
        const val DEFAULT_IDLE_TIMEOUT_MS = 15_000L
        const val POSITION_EPSILON = 1e-6
        const val ZOOM_EPSILON = 1e-3
        const val ANGLE_EPSILON = 1e-3
    }
}
