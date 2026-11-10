package nl.r1a.grassmap.plugin.api.error

import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.SwitchId

/**
 * A typed error at a module boundary. It carries data, never a user-facing string.
 */
sealed interface AppError {
    data object InvalidInput : AppError

    data object NotFound : AppError

    data object NotReady : AppError

    data object PermissionDenied : AppError

    data class SwitchOff(
        val switch: SwitchId,
    ) : AppError

    data object Network : AppError

    data class Server(
        val status: Int,
        val retryAfter: Long? = null,
    ) : AppError

    data object CorruptData : AppError

    data object Unsupported : AppError

    data object Cancelled : AppError

    data class PluginFailed(
        val pluginId: PluginId,
    ) : AppError

    /**
     * A routing pack is absent, incomplete or unreadable, so no engine can
     * serve a route. [detail] is diagnostic data for the log, never user text.
     */
    data class RoutingPackUnavailable(
        val reason: Reason,
        val detail: String? = null,
    ) : AppError {
        enum class Reason {
            /** No pack directory, or nothing mounted at the path. */
            Absent,

            /** The directory exists but a required file is missing. */
            Incomplete,

            /** Every required file exists but the engine cannot read them. */
            Unusable,
        }
    }

    data class Internal(
        val id: String,
        val detail: String? = null,
    ) : AppError
}
