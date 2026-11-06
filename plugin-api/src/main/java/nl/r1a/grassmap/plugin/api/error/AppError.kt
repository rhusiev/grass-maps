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

    data class Internal(
        val id: String,
        val detail: String? = null,
    ) : AppError
}
