package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.error.Outcome

/** One authenticated GET. [token] is absent for a request that carries no credential. */
data class DownloadRequest(
    val url: String,
    val token: BearerToken?,
    val policy: DownloadPolicy,
)

/** A response that was not a success status; its body is never consumed. */
data class TransportResult(
    val status: Int,
    val retryAfterSeconds: Long? = null,
) {
    val isSuccess: Boolean
        get() = status in SUCCESS_RANGE

    private companion object {
        val SUCCESS_RANGE: IntRange = 200..299
    }
}

/**
 * Receives a response body in chunks. Returning a failure stops the transfer,
 * and cancellation is checked by the caller's sink.
 */
fun interface BodySink {
    suspend fun write(
        buffer: ByteArray,
        length: Int,
    ): Outcome<Unit>
}

/**
 * The seam between the package pipeline and HTTP. The real implementation is
 * [HttpDownloadTransport]; tests substitute a fake. A transport never throws
 * across this boundary: a socket, timeout or policy failure is an
 * [nl.r1a.grassmap.plugin.api.error.AppError] value.
 */
interface DownloadTransport {
    suspend fun get(
        request: DownloadRequest,
        sink: BodySink,
    ): Outcome<TransportResult>
}
