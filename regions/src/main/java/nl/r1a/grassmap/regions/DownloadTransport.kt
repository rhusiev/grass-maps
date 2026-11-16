package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * One authenticated GET. [token] is absent for a request that carries no credential.
 *
 * A [rangeStart] above zero asks for the body from that byte on with an HTTP
 * `Range` header. The transport accepts a `206` only when its `Content-Range`
 * starts at [rangeStart] and runs to the end of the representation. A `200`
 * means the server ignored the range and sends the whole body, which
 * [BodySink.begin] reports as offset 0.
 */
data class DownloadRequest(
    val url: String,
    val token: BearerToken?,
    val policy: DownloadPolicy,
    val rangeStart: Long = 0,
)

/** A response that was not a success status; its body is never consumed. */
data class TransportResult(
    val status: Int,
    val retryAfterSeconds: Long? = null,
    /** The full size a `416` named in `Content-Range` of the form "bytes star slash size", when it did. */
    val totalBytes: Long? = null,
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

    /**
     * Called once before the first [write] of an accepted response. [offset] is
     * the archive byte the body starts at: [DownloadRequest.rangeStart] for a
     * verified `206`, and 0 for every other success, including a `200` that
     * answered a range request.
     */
    suspend fun begin(offset: Long): Outcome<Unit> = Outcome.Success(Unit)
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
