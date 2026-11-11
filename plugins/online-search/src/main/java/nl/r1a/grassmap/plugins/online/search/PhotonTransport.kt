package nl.r1a.grassmap.plugins.online.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * One HTTP GET for the Photon provider. Tests replace it with a recorder, so
 * no unit test opens a socket.
 */
fun interface PhotonTransport {
    suspend fun get(url: String): Outcome<String>
}

/**
 * The production transport. It is only reached inside a request the
 * [nl.r1a.grassmap.plugin.api.service.NetworkGate] allowed.
 *
 * The public Photon instance asks clients to identify themselves, so the
 * request carries a product User-Agent. Timeouts bound a blocking socket that
 * cancellation cannot abort immediately.
 */
class HttpPhotonTransport(
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
) : PhotonTransport {
    override suspend fun get(url: String): Outcome<String> =
        withContext(Dispatchers.IO) {
            val connection =
                try {
                    URL(url).openConnection() as? HttpURLConnection
                } catch (exception: IOException) {
                    null
                } ?: return@withContext Outcome.Failure(AppError.Network)
            try {
                connection.requestMethod = METHOD_GET
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.setRequestProperty(ACCEPT_HEADER, ACCEPT_VALUE)
                connection.setRequestProperty(USER_AGENT_HEADER, USER_AGENT)
                val status = connection.responseCode
                if (status !in SUCCESS_RANGE) {
                    return@withContext Outcome.Failure(
                        AppError.Server(status = status, retryAfter = retryAfterSeconds(connection)),
                    )
                }
                readBody(connection)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IOException) {
                Outcome.Failure(AppError.Network)
            } catch (exception: SecurityException) {
                Outcome.Failure(AppError.Network)
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Reads a response with a bound. Photon is untrusted input: a body far
     * larger than a result page is a corrupt response, not a result set.
     */
    private fun readBody(connection: HttpURLConnection): Outcome<String> {
        val declaredLength = connection.getHeaderField(CONTENT_LENGTH_HEADER)?.trim()?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_BODY_CHARS) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val body = StringBuilder()
        val buffer = CharArray(READ_BUFFER_CHARS)
        connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) {
                    break
                }
                body.append(buffer, 0, read)
                if (body.length > MAX_BODY_CHARS) {
                    return Outcome.Failure(AppError.CorruptData)
                }
            }
        }
        return Outcome.Success(body.toString())
    }

    private fun retryAfterSeconds(connection: HttpURLConnection): Long? =
        connection.getHeaderField(RETRY_AFTER_HEADER)?.trim()?.toLongOrNull()

    private companion object {
        const val METHOD_GET = "GET"
        const val ACCEPT_HEADER = "Accept"
        const val ACCEPT_VALUE = "application/json"
        const val USER_AGENT_HEADER = "User-Agent"
        const val USER_AGENT = "GrassMaps/0.1.0 (Android; online place search)"
        const val RETRY_AFTER_HEADER = "Retry-After"
        const val CONTENT_LENGTH_HEADER = "Content-Length"
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 5_000
        const val MAX_BODY_CHARS = 1_000_000
        const val READ_BUFFER_CHARS = 4_096
        val SUCCESS_RANGE = 200..299
    }
}
