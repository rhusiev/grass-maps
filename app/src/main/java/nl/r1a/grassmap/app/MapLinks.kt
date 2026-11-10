package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugins.links.LinkResult
import nl.r1a.grassmap.plugins.links.LinkRouter
import nl.r1a.grassmap.plugins.links.LinkTarget
import nl.r1a.grassmap.plugins.map.controls.CameraModeController

/**
 * Turns a link that another app sent into a camera move (T-0211).
 *
 * The Activity can hand over the intent URI before any map surface exists, so a
 * parsed point is parked and replayed when a surface binds. A link can also
 * arrive while a surface is already bound, and then no composition key changes:
 * [pendingMoves] carries that move to the screen's collector, so a running
 * instance moves the camera without a recomposition. The move goes through
 * [applyPending] once, which takes the parked value, leaves follow and heading
 * mode, and asks the host to move; the next fix would otherwise drag the camera
 * back off the linked point.
 */
class MapLinks(
    private val session: MapSessionHolder,
    private val modes: CameraModeController,
    private val log: (String) -> Unit = {},
) {
    /**
     * The newest move not yet applied. A StateFlow conflates two links that
     * arrive before a surface binds, so the last one is the one that is shown.
     */
    private val pending = MutableStateFlow<CameraUpdate?>(null)

    /** Moves for the screen to apply; each emission is taken exactly once. */
    val pendingMoves: Flow<CameraUpdate> = pending.filterNotNull()

    /** The move waiting for a bound surface, if any; for diagnostics and tests. */
    val pendingMove: CameraUpdate?
        get() = pending.value

    /**
     * Parses [uri] and parks its camera move. A link that names an element
     * without coordinates opens the map where it is: this app has no offline
     * element lookup, so it draws no pin and guesses no point.
     */
    fun submit(uri: String?): LinkResult? {
        if (uri == null) {
            return null
        }
        return when (val result = LinkRouter.route(uri)) {
            is LinkResult.Failure -> {
                log("link refused: ${result.reason}")
                result
            }

            is LinkResult.Success -> {
                queue(result.target)
                result
            }
        }
    }

    /**
     * Hands the parked move to [host] and reports the move, or null when
     * nothing was parked. Taking the value before the move is what stops an
     * older move from being applied twice. The screen shows a failed move to
     * the user; a failure here would otherwise leave the map at its old
     * position with no answer.
     */
    suspend fun applyPending(host: MapHost): Outcome<Unit>? {
        val update = takePending() ?: return null
        when (val outcome = modes.free()) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> log("link kept the camera mode: ${outcome.error}")
        }
        return when (val outcome = host.moveCamera(update)) {
            is Outcome.Success -> {
                log("link centred the map on ${update.center}")
                outcome
            }

            is Outcome.Failure -> {
                log("link camera move failed: ${outcome.error}")
                outcome
            }
        }
    }

    /** Removes and returns the parked move, or null when there is none. */
    private fun takePending(): CameraUpdate? {
        while (true) {
            val current = pending.value ?: return null
            if (pending.compareAndSet(current, null)) {
                return current
            }
        }
    }

    private fun queue(target: LinkTarget) {
        when (target) {
            is LinkTarget.Point -> {
                pending.value = CameraUpdate(center = target.point, zoom = clampZoom(target.zoom))
            }

            is LinkTarget.Element -> {
                val point = target.point
                if (point == null) {
                    log(
                        "element link ${target.type}/${target.id} has no coordinates; " +
                            "opening the map without a pin",
                    )
                    return
                }
                pending.value = CameraUpdate(center = point, zoom = clampZoom(target.zoom))
            }
        }
    }

    private fun clampZoom(zoom: Double?): Double? = zoom?.coerceIn(MIN_LINK_ZOOM, MAX_LINK_ZOOM)

    private companion object {
        /** The zoom range the map itself accepts; a link outside it is clamped. */
        const val MIN_LINK_ZOOM = 1.0
        const val MAX_LINK_ZOOM = 20.0
    }
}
