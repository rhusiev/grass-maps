package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.contract.SwitchId

/**
 * The bounds one download runs under. The privacy switch is the T-0016 switch
 * the request names; every other value is a limit the transport or the package
 * reader enforces.
 *
 * Values are clamped before use, so a caller can narrow a bound but cannot
 * remove it. Cleartext HTTP is off unless a caller explicitly asks for it; the
 * debug trigger is the only such caller, and it logs the choice.
 */
data class DownloadPolicy(
    val switch: SwitchId,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
    val allowCrossHostRedirect: Boolean = false,
    val allowCleartextHttp: Boolean = false,
    val maxPackageBytes: Long = DEFAULT_MAX_PACKAGE_BYTES,
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    val maxFiles: Int = DEFAULT_MAX_FILES,
) {
    val boundedConnectTimeoutMs: Int
        get() = connectTimeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    val boundedReadTimeoutMs: Int
        get() = readTimeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    val boundedMaxRedirects: Int
        get() = maxRedirects.coerceIn(0, MAX_REDIRECTS_LIMIT)

    val boundedMaxPackageBytes: Long
        get() = maxPackageBytes.coerceIn(1, MAX_PACKAGE_BYTES_LIMIT)

    val boundedMaxFileBytes: Long
        get() = maxFileBytes.coerceIn(1, MAX_FILE_BYTES_LIMIT)

    val boundedMaxFiles: Int
        get() = maxFiles.coerceIn(1, MAX_FILES_LIMIT)

    companion object {
        const val MIN_TIMEOUT_MS: Int = 1_000
        const val MAX_TIMEOUT_MS: Int = 60_000
        const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 10_000
        const val DEFAULT_READ_TIMEOUT_MS: Int = 30_000

        const val DEFAULT_MAX_REDIRECTS: Int = 3
        const val MAX_REDIRECTS_LIMIT: Int = 5

        const val MEBIBYTE_BYTES: Long = 1024L * 1024L
        const val GIBIBYTE_BYTES: Long = 1024L * MEBIBYTE_BYTES

        const val DEFAULT_MAX_PACKAGE_BYTES: Long = 2L * GIBIBYTE_BYTES
        const val MAX_PACKAGE_BYTES_LIMIT: Long = 4L * GIBIBYTE_BYTES
        const val DEFAULT_MAX_FILE_BYTES: Long = 2L * GIBIBYTE_BYTES
        const val MAX_FILE_BYTES_LIMIT: Long = 4L * GIBIBYTE_BYTES

        const val DEFAULT_MAX_FILES: Int = 1024
        const val MAX_FILES_LIMIT: Int = 4096

        /** A manifest is small; anything larger is not one. */
        const val MAX_MANIFEST_BYTES: Long = 1L * MEBIBYTE_BYTES
    }
}
