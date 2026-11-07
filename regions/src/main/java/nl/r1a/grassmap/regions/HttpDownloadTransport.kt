package nl.r1a.grassmap.regions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import kotlin.coroutines.coroutineContext

/**
 * The production [DownloadTransport]: one GET on [Dispatchers.IO], bounded
 * timeouts, and no redirect unless the policy permits it.
 *
 * Redirects are handled here rather than by [HttpURLConnection], so a redirect
 * to another origin is a policy decision and not a silent follow. The bearer
 * token is attached only while the request stays on the initial origin, never
 * to a cross-host or cross-port location.
 */
class HttpDownloadTransport : DownloadTransport {
    override suspend fun get(
        request: DownloadRequest,
        sink: BodySink,
    ): Outcome<TransportResult> =
        withContext(Dispatchers.IO) {
            try {
                transfer(request, sink)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IOException) {
                Outcome.Failure(AppError.Network)
            } catch (exception: SecurityException) {
                Outcome.Failure(AppError.Network)
            }
        }

    private suspend fun transfer(
        request: DownloadRequest,
        sink: BodySink,
    ): Outcome<TransportResult> {
        val policy = request.policy
        val initial =
            when (val checked = checkedUri(request.url, policy)) {
                is Outcome.Success -> checked.value
                is Outcome.Failure -> return checked
            }
        var current = initial
        var redirects = 0
        while (true) {
            val connection =
                current.toURL().openConnection() as? HttpURLConnection
                    ?: return Outcome.Failure(AppError.Network)
            try {
                connection.requestMethod = GET
                connection.instanceFollowRedirects = false
                connection.connectTimeout = policy.boundedConnectTimeoutMs
                connection.readTimeout = policy.boundedReadTimeoutMs
                connection.setRequestProperty(ACCEPT_HEADER, ACCEPT_VALUE)
                connection.setRequestProperty(CONNECTION_HEADER, CONNECTION_CLOSE)
                if (sameAuthority(current, initial)) {
                    request.token?.let { connection.setRequestProperty(AUTHORIZATION_HEADER, it.authorizationHeader) }
                }
                val status = connection.responseCode
                if (status in REDIRECT_STATUSES) {
                    val next = redirectTarget(current, connection, policy) ?: return Outcome.Failure(AppError.Network)
                    if (redirects >= policy.boundedMaxRedirects) {
                        return Outcome.Failure(AppError.Network)
                    }
                    redirects++
                    current = next
                    continue
                }
                if (status !in SUCCESS_RANGE) {
                    return Outcome.Success(
                        TransportResult(status = status, retryAfterSeconds = retryAfterSeconds(connection)),
                    )
                }
                return readBody(connection, status, sink, policy)
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun readBody(
        connection: HttpURLConnection,
        status: Int,
        sink: BodySink,
        policy: DownloadPolicy,
    ): Outcome<TransportResult> {
        val declared = connection.contentLengthLong
        if (declared > policy.boundedMaxPackageBytes) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val input = connection.inputStream
        return try {
            copyBody(input, declared, sink, policy.boundedMaxPackageBytes)
        } finally {
            input.close()
        }.let { copied ->
            when (copied) {
                is Outcome.Failure -> copied
                is Outcome.Success -> Outcome.Success(TransportResult(status))
            }
        }
    }

    private suspend fun copyBody(
        input: InputStream,
        declared: Long,
        sink: BodySink,
        maxBytes: Long,
    ): Outcome<Unit> {
        val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            if (read == 0) {
                continue
            }
            total += read
            if (total > maxBytes) {
                return Outcome.Failure(AppError.CorruptData)
            }
            when (val written = sink.write(buffer, read)) {
                is Outcome.Failure -> return written
                is Outcome.Success -> Unit
            }
        }
        if (declared >= 0 && total != declared) {
            return Outcome.Failure(AppError.Network)
        }
        return Outcome.Success(Unit)
    }

    private fun redirectTarget(
        current: URI,
        connection: HttpURLConnection,
        policy: DownloadPolicy,
    ): URI? {
        val location = connection.getHeaderField(LOCATION_HEADER) ?: return null
        val next =
            try {
                current.resolve(location)
            } catch (exception: IllegalArgumentException) {
                return null
            }
        val checked = checkedUri(next.toString(), policy)
        if (checked !is Outcome.Success) {
            return null
        }
        if (!policy.allowCrossHostRedirect && !sameAuthority(next, current)) {
            return null
        }
        return checked.value
    }

    /** Host and effective port; a redirect across either is a different origin. */
    private fun sameAuthority(
        left: URI,
        right: URI,
    ): Boolean = left.host == right.host && effectivePort(left) == effectivePort(right)

    private fun effectivePort(uri: URI): Int =
        when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals(HTTPS_SCHEME, ignoreCase = true) -> HTTPS_PORT
            else -> HTTP_PORT
        }

    private fun checkedUri(
        value: String,
        policy: DownloadPolicy,
    ): Outcome<URI> {
        val uri =
            try {
                URI(value)
            } catch (exception: URISyntaxException) {
                return Outcome.Failure(AppError.InvalidInput)
            }
        if (!uri.isAbsolute || uri.host.isNullOrBlank() || uri.userInfo != null) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        return when (uri.scheme?.lowercase()) {
            HTTPS_SCHEME -> {
                Outcome.Success(uri)
            }

            HTTP_SCHEME -> {
                if (policy.allowCleartextHttp) {
                    Outcome.Success(uri)
                } else {
                    Outcome.Failure(AppError.Network)
                }
            }

            else -> {
                Outcome.Failure(AppError.InvalidInput)
            }
        }
    }

    private fun retryAfterSeconds(connection: HttpURLConnection): Long? =
        connection
            .getHeaderField(RETRY_AFTER_HEADER)
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }

    private companion object {
        val GET = "GET"
        const val HTTP_SCHEME = "http"
        const val HTTPS_SCHEME = "https"
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443
        const val TRANSFER_BUFFER_BYTES = 64 * 1024
        const val ACCEPT_HEADER = "Accept"
        const val ACCEPT_VALUE = "application/zip"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val CONNECTION_HEADER = "Connection"
        const val CONNECTION_CLOSE = "close"
        const val LOCATION_HEADER = "Location"
        const val RETRY_AFTER_HEADER = "Retry-After"
        val SUCCESS_RANGE: IntRange = 200..299
        val REDIRECT_STATUSES: Set<Int> = setOf(301, 302, 303, 307, 308)
    }
}
