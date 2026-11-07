package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.DevToolsAccess
import nl.r1a.grassmap.plugin.api.host.DevToolsMetrics

/**
 * Starts and stops the GPX replay the app's location source already runs. The
 * debug source and the release source both implement it, so the dev-tools
 * plugin drives one seam in either build.
 */
internal interface ReplayControl {
    fun isReplaying(): Boolean

    fun startReplay()

    fun stopReplay()
}

/** [DevToolsAccess] plus the frame hook the map feeds while the panel is on. */
interface DevToolsRuntime : DevToolsAccess {
    fun onFrame()
}

/** The app-side dev-tools access over the active replay source and [RuntimeMetrics]. */
internal class AppDevTools(
    private val replay: ReplayControl,
    private val metrics: RuntimeMetrics,
) : DevToolsRuntime {
    override fun onFrame() = metrics.onFrame()

    override fun isReplayActive(): Boolean = replay.isReplaying()

    override fun metrics(): DevToolsMetrics = metrics.snapshot()

    override suspend fun startReplay(): Outcome<Unit> {
        replay.startReplay()
        return Outcome.Success(Unit)
    }

    override fun stopReplay() = replay.stopReplay()
}

/** The inert runtime a build has when it wires no replay source. */
object NoDevToolsRuntime : DevToolsRuntime {
    override fun onFrame() = Unit

    override fun isReplayActive(): Boolean = false

    override fun metrics(): DevToolsMetrics = DevToolsMetrics(framesPerSecond = 0.0, usedMemoryBytes = 0L)

    override suspend fun startReplay(): Outcome<Unit> = Outcome.Failure(AppError.Unsupported)

    override fun stopReplay() = Unit
}
