package nl.r1a.grassmap.plugins.online.search

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.provider.Place
import nl.r1a.grassmap.plugin.api.provider.PlaceDetails
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.PlaceProvider
import nl.r1a.grassmap.plugin.api.provider.PlaceProviderContract
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import nl.r1a.grassmap.plugin.api.service.NetworkGate
import nl.r1a.grassmap.plugin.api.service.NetworkSwitches
import nl.r1a.grassmap.plugin.api.service.NoNetworkGate
import java.net.URLEncoder

/**
 * The `places.search` provider over the public Photon geocoder (T-0212).
 *
 * The privacy switch owns permission to leave the device: the gate refuses
 * before any URL is fetched, and a fake transport proves zero calls. The
 * caller decides whether the query carries a map position; this provider never
 * adds `lat`/`lon` on its own.
 *
 * Public-service policy. Photon asks for fair use and throttles extensive
 * traffic, so this provider sends at most one request per second, retries once
 * after 750 ms, and never retries a refusal or a corrupt body. The search
 * layer debounces keystrokes separately.
 */
class PhotonPlaceProvider(
    private val gate: NetworkGate = NoNetworkGate,
    private val transport: PhotonTransport = HttpPhotonTransport(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val minIntervalMillis: Long = MIN_INTERVAL_MILLIS,
    private val retryDelayMillis: Long = RETRY_DELAY_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { millis -> delay(millis) },
) : PlaceProvider {
    override val contract = PlaceProviderContract.REFERENCE

    private val rateMutex = Mutex()
    private var lastRequestStartedAt: Long? = null

    override suspend fun search(
        query: String,
        context: SearchContext,
        limit: Int,
        cancellation: CancellationHandle,
    ): Outcome<List<PlaceHit>> {
        if (query.isBlank() || limit !in 1..MAX_RESULTS) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val viewport = context.viewport
        if (viewport != null && !viewport.isValid()) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        if (cancellation.isCancelled()) {
            return Outcome.Failure(AppError.Cancelled)
        }
        return gate.request(NetworkSwitches.onlineSearch) {
            fetch(query.trim(), context, limit, viewport, cancellation)
        }
    }

    override suspend fun pois(
        bbox: BoundingBox,
        categories: Set<String>,
        limit: Int,
        cancellation: CancellationHandle,
    ): Outcome<List<Place>> = Outcome.Failure(AppError.Unsupported)

    override suspend fun details(
        placeId: String,
        cancellation: CancellationHandle,
    ): Outcome<PlaceDetails> = Outcome.Failure(AppError.NotFound)

    private suspend fun fetch(
        query: String,
        context: SearchContext,
        limit: Int,
        viewport: BoundingBox?,
        cancellation: CancellationHandle,
    ): Outcome<List<PlaceHit>> {
        val url = buildUrl(query, context, limit)
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            if (cancellation.isCancelled()) {
                return Outcome.Failure(AppError.Cancelled)
            }
            awaitRateLimit(cancellation)
            if (cancellation.isCancelled()) {
                return Outcome.Failure(AppError.Cancelled)
            }
            val body =
                when (val response = transport.get(url)) {
                    is Outcome.Success -> {
                        response.value
                    }

                    is Outcome.Failure -> {
                        attempt += 1
                        if (attempt >= MAX_ATTEMPTS || !isRetryable(response.error)) {
                            return response
                        }
                        pause(retryDelay(response.error))
                        continue
                    }
                }
            if (cancellation.isCancelled()) {
                return Outcome.Failure(AppError.Cancelled)
            }
            val mapped = PhotonMapping.toHits(body, limit)
            return when (mapped) {
                is Outcome.Failure -> mapped
                is Outcome.Success -> Outcome.Success(filterViewport(mapped.value, viewport))
            }
        }
        return Outcome.Failure(AppError.Network)
    }

    private suspend fun awaitRateLimit(cancellation: CancellationHandle) {
        rateMutex.withLock {
            val previous = lastRequestStartedAt
            if (previous != null && minIntervalMillis > 0) {
                val wait = minIntervalMillis - (clock() - previous)
                if (wait > 0) {
                    pause(wait)
                }
            }
            if (cancellation.isCancelled()) {
                return
            }
            lastRequestStartedAt = clock()
        }
    }

    private fun buildUrl(
        query: String,
        context: SearchContext,
        limit: Int,
    ): String {
        val parameters = mutableListOf(PARAM_QUERY to query, PARAM_LIMIT to limit.toString())
        context.language?.trim()?.takeIf { it.isNotEmpty() }?.let { language ->
            parameters += PARAM_LANGUAGE to language
        }
        context.position?.let { position ->
            parameters += PARAM_LATITUDE to position.latitude.toString()
            parameters += PARAM_LONGITUDE to position.longitude.toString()
        }
        val separator = if (baseUrl.contains('?')) '&' else '?'
        return baseUrl.trimEnd('?') +
            separator +
            parameters.joinToString(separator = "&") { (key, value) -> "${encode(key)}=${encode(value)}" }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, ENCODING)

    private fun filterViewport(
        hits: List<PlaceHit>,
        viewport: BoundingBox?,
    ): List<PlaceHit> = if (viewport == null) hits else hits.filter { hit -> viewport.contains(hit.position) }

    private fun isRetryable(error: AppError): Boolean =
        when (error) {
            AppError.Network -> true
            is AppError.Server -> error.status == STATUS_TIMEOUT || error.status == STATUS_TOO_MANY || error.status in SERVER_ERRORS
            else -> false
        }

    private fun retryDelay(error: AppError): Long {
        val requested = (error as? AppError.Server)?.retryAfter?.times(MILLIS_PER_SECOND) ?: 0L
        return maxOf(retryDelayMillis, requested).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
    }

    companion object {
        /** Public Photon; the only endpoint the app names. */
        const val DEFAULT_BASE_URL: String = "https://photon.komoot.io/api/"

        /** One request per second, well inside the public instance's fair-use limit. */
        const val MIN_INTERVAL_MILLIS: Long = 1_000

        /** One retry after a short pause; the total is two attempts. */
        const val RETRY_DELAY_MILLIS: Long = 750

        /** A Retry-After header cannot stall a keystroke longer than this. */
        const val MAX_RETRY_DELAY_MILLIS: Long = 5_000

        const val MAX_ATTEMPTS: Int = 2

        /** Photon returns at most a page this size; a caller asking for more is clipped. */
        const val MAX_RESULTS: Int = 20

        private const val PARAM_QUERY = "q"
        private const val PARAM_LIMIT = "limit"
        private const val PARAM_LANGUAGE = "lang"
        private const val PARAM_LATITUDE = "lat"
        private const val PARAM_LONGITUDE = "lon"

        private const val STATUS_TIMEOUT = 408
        private const val STATUS_TOO_MANY = 429
        private val SERVER_ERRORS = 500..599
        private const val MILLIS_PER_SECOND = 1_000L
        private const val ENCODING = "UTF-8"
    }
}
