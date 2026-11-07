package nl.r1a.grassmap.plugin.api.host

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome

/** One runtime sample for the development-tools panel. */
data class DevToolsMetrics(
    val framesPerSecond: Double,
    val usedMemoryBytes: Long,
)

/**
 * The host seams the release development-tools plugin reads (T-0114).
 *
 * The replay it starts is the app's existing location source, not a second
 * position path. Frame rate and memory are values the host already samples, so
 * the plugin adds no analytics and no network access of its own.
 */
interface DevToolsAccess {
    /** Whether the GPX replay is the active location source. */
    fun isReplayActive(): Boolean

    /** The latest frame-rate and memory sample. */
    fun metrics(): DevToolsMetrics

    /** Switches the location source to the GPX replay. */
    suspend fun startReplay(): Outcome<Unit>

    /** Switches the location source back to the platform source. */
    fun stopReplay()
}

/** The inert access a host without a replay source or metrics provides. */
object NoDevToolsAccess : DevToolsAccess {
    override fun isReplayActive(): Boolean = false

    override fun metrics(): DevToolsMetrics = DevToolsMetrics(framesPerSecond = 0.0, usedMemoryBytes = 0L)

    override suspend fun startReplay(): Outcome<Unit> = Outcome.Failure(AppError.Unsupported)

    override fun stopReplay() = Unit
}
