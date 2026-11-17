package nl.r1a.grassmap.plugins.raster

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One fetched tile: the bytes the engine would draw and their media type. */
data class TileImage(
    val bytes: ByteArray,
    val contentType: String,
)

/**
 * One HTTP GET for a raster tile. Tests replace it with a fixture or a
 * recorder, so no unit test opens a remote socket.
 */
fun interface RasterTileTransport {
    suspend fun get(
        url: String,
        userAgent: String,
    ): Outcome<TileImage>
}

/**
 * The production tile transport. It is reached only inside a request the
 * [nl.r1a.grassmap.plugin.api.service.NetworkGate] allowed.
 *
 * The OSM tile policy asks for an identifying User-Agent, so every request
 * carries one. There is no retry, no prefetch and no cache here: the engine
 * asks for the tiles the viewport needs, and its own cache keeps them.
 * A redirect is followed only within the request's own host.
 */
class HttpRasterTileTransport(
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
) : RasterTileTransport {
    override suspend fun get(
        url: String,
        userAgent: String,
    ): Outcome<TileImage> =
        withContext(Dispatchers.IO) {
            var target = url
            repeat(MAX_REDIRECTS + 1) {
                val connection =
                    try {
                        URL(target).openConnection() as? HttpURLConnection
                    } catch (exception: IOException) {
                        null
                    } ?: return@withContext Outcome.Failure(AppError.Network)
                try {
                    configure(connection, userAgent)
                    val status = connection.responseCode
                    if (status in REDIRECT_STATUSES) {
                        target = sameHostRedirect(target, connection.getHeaderField(LOCATION_HEADER))
                            ?: return@withContext Outcome.Failure(AppError.Server(status = status))
                    } else if (status !in SUCCESS_RANGE) {
                        val retryAfter = connection.getHeaderField(RETRY_AFTER_HEADER)?.trim()?.toLongOrNull()
                        return@withContext Outcome.Failure(AppError.Server(status = status, retryAfter = retryAfter))
                    } else {
                        return@withContext readTile(connection)
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: IOException) {
                    return@withContext Outcome.Failure(AppError.Network)
                } catch (exception: SecurityException) {
                    return@withContext Outcome.Failure(AppError.Network)
                } finally {
                    connection.disconnect()
                }
            }
            Outcome.Failure(AppError.Network)
        }

    private fun configure(
        connection: HttpURLConnection,
        userAgent: String,
    ) {
        connection.requestMethod = METHOD_GET
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.setRequestProperty(ACCEPT_HEADER, ACCEPT_VALUE)
        connection.setRequestProperty(USER_AGENT_HEADER, userAgent)
    }

    /**
     * The redirect target when it stays on the request's own host, else null.
     * A tile source may move a path. It may not send the client, and its
     * User-Agent, to another host.
     */
    private fun sameHostRedirect(
        current: String,
        location: String?,
    ): String? {
        if (location == null) {
            return null
        }
        return try {
            val from = URL(current)
            val to = URL(from, location)
            to.toString().takeIf { to.host.equals(from.host, ignoreCase = true) && to.protocol in SAFE_PROTOCOLS }
        } catch (exception: IOException) {
            null
        }
    }

    private fun readTile(connection: HttpURLConnection): Outcome<TileImage> {
        val contentType = connection.contentType.orEmpty()
        if (!contentType.startsWith(IMAGE_PREFIX)) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val declaredLength = connection.getHeaderField(CONTENT_LENGTH_HEADER)?.trim()?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_TILE_BYTES) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        connection.inputStream.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) {
                    break
                }
                output.write(buffer, 0, read)
                if (output.size() > MAX_TILE_BYTES) {
                    return Outcome.Failure(AppError.CorruptData)
                }
            }
        }
        if (output.size() == 0) {
            return Outcome.Failure(AppError.CorruptData)
        }
        return Outcome.Success(TileImage(bytes = output.toByteArray(), contentType = contentType))
    }

    private companion object {
        const val METHOD_GET = "GET"
        const val ACCEPT_HEADER = "Accept"
        const val ACCEPT_VALUE = "image/*"
        const val USER_AGENT_HEADER = "User-Agent"
        const val CONTENT_LENGTH_HEADER = "Content-Length"
        const val LOCATION_HEADER = "Location"
        const val RETRY_AFTER_HEADER = "Retry-After"
        const val MAX_REDIRECTS = 3
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        val SAFE_PROTOCOLS = setOf("http", "https")
        const val IMAGE_PREFIX = "image/"
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 5_000

        /** A tile far larger than any 256 or 512 px PNG/JPEG is corrupt. */
        const val MAX_TILE_BYTES = 1_000_000
        const val READ_BUFFER_BYTES = 16_384
        val SUCCESS_RANGE = 200..299
    }
}
