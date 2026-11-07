package nl.r1a.grassmap.core.services

/**
 * The device facts a diagnostic bundle carries. It holds no identifier: no
 * advertising id, no Android id, no account and no serial number.
 */
data class DeviceInfo(
    val model: String,
    val osVersion: String,
    val appVersion: String,
) {
    fun asText(): String =
        buildString {
            appendLine("model=$model")
            appendLine("os=$osVersion")
            appendLine("app=$appVersion")
        }

    companion object {
        val UNKNOWN: DeviceInfo = DeviceInfo(model = "unknown", osVersion = "unknown", appVersion = "unknown")
    }
}
