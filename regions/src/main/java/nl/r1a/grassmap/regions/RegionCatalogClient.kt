package nl.r1a.grassmaps.regions

import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.DownloadsDefaults
import nl.r1a.grassmaps.plugin.api.model.RegionCatalog
import java.io.ByteArrayOutputStream

/**
 * Fetches and parses the server's catalogue (server-protocol.md section 1).
 *
 * The body is read through the same [DownloadTransport] as a package, so the
 * privacy switch, the redirect policy, the timeouts and the bearer token behave
 * identically. A catalogue larger than [DownloadsDefaults.MAX_CATALOG_BYTES] is
 * not one; the transfer stops there instead of buffering it.
 */
internal class RegionCatalogClient(
    private val transport: DownloadTransport,
) {
    suspend fun fetch(
        baseUrl: String,
        token: BearerToken?,
        policy: DownloadPolicy,
    ): Outcome<RegionCatalog> {
        val url = RegionServerUrls.catalog(baseUrl) ?: return Outcome.Failure(AppError.InvalidInput)
        val body = ByteArrayOutputStream()
        val result =
            transport.get(DownloadRequest(url = url, token = token, policy = policy)) { buffer, length ->
                if (body.size().toLong() + length > DownloadsDefaults.MAX_CATALOG_BYTES) {
                    return@get Outcome.Failure(AppError.CorruptData)
                }
                body.write(buffer, 0, length)
                Outcome.Success(Unit)
            }
        return when (result) {
            is Outcome.Failure -> {
                result
            }

            is Outcome.Success -> {
                if (!result.value.isSuccess) {
                    Outcome.Failure(
                        AppError.Server(
                            status = result.value.status,
                            retryAfter = result.value.retryAfterSeconds,
                        ),
                    )
                } else {
                    RegionCatalogParser.parse(body.toByteArray())
                }
            }
        }
    }
}

/**
 * Fetches one region's border file (server-protocol.md section 2, `outline`).
 *
 * The body goes through the same [DownloadTransport] as the catalogue, so the
 * privacy gate and the token behave the same. A body larger than
 * [DownloadsDefaults.MAX_OUTLINE_BYTES] is not an outline.
 */
internal class RegionOutlineClient(
    private val transport: DownloadTransport,
) {
    suspend fun fetch(
        baseUrl: String,
        path: String,
        token: BearerToken?,
        policy: DownloadPolicy,
    ): Outcome<ByteArray> {
        val url = RegionServerUrls.relative(baseUrl, path) ?: return Outcome.Failure(AppError.InvalidInput)
        val body = ByteArrayOutputStream()
        val result =
            transport.get(DownloadRequest(url = url, token = token, policy = policy)) { buffer, length ->
                if (body.size().toLong() + length > DownloadsDefaults.MAX_OUTLINE_BYTES) {
                    return@get Outcome.Failure(AppError.CorruptData)
                }
                body.write(buffer, 0, length)
                Outcome.Success(Unit)
            }
        return when (result) {
            is Outcome.Failure -> {
                result
            }

            is Outcome.Success -> {
                if (result.value.isSuccess) {
                    Outcome.Success(body.toByteArray())
                } else {
                    Outcome.Failure(AppError.Server(status = result.value.status, retryAfter = result.value.retryAfterSeconds))
                }
            }
        }
    }
}

/**
 * The URLs the app asks a server for. [base] is the user-supplied server
 * address; a trailing slash is allowed, and a region and version are validated
 * path segments before either reaches a URL.
 */
internal object RegionServerUrls {
    fun catalog(base: String): String? = base(base)?.let { "$it/${DownloadsDefaults.CATALOG_FILE}" }

    /** [path] is a catalogue-relative path the parser already checked for escapes. */
    fun relative(
        base: String,
        path: String,
    ): String? = base(base)?.let { "$it/$path" }

    fun packageArchive(
        base: String,
        region: String,
        version: String,
    ): String? {
        if (!RegionIdentifiers.isSafeSegment(region) || !RegionIdentifiers.isSafeSegment(version)) {
            return null
        }
        return base(base)?.let { "$it/$region/$version/${DownloadsDefaults.PACKAGE_FILE}" }
    }

    private fun base(value: String): String? = value.trim().trimEnd('/').takeIf { it.isNotEmpty() }
}
