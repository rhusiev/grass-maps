package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.DownloadsDefaults
import nl.r1a.grassmap.plugin.api.model.RegionCatalog
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
 * The two URLs the app asks a server for. [base] is the user-supplied server
 * address; a trailing slash is allowed, and a region and version are validated
 * path segments before either reaches a URL.
 */
internal object RegionServerUrls {
    fun catalog(base: String): String? = base(base)?.let { "$it/${DownloadsDefaults.CATALOG_FILE}" }

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
