package nl.r1a.grassmap.regions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID

/** One package to download. Region and version are validated before any path is built. */
data class RegionRequest(
    val url: String,
    val region: String,
    val version: String,
    val token: BearerToken?,
)

/**
 * The regions download pipeline: gate the request, download the zip, extract to
 * a staging directory under `.incoming`, verify the manifest and every declared
 * file, then install atomically.
 *
 * The token never reaches a log line; only its presence is reported.
 */
class RegionDownloader(
    private val transport: DownloadTransport,
    private val paths: RegionPaths,
    private val log: (String) -> Unit = {},
) {
    private val installMutex = Mutex()
    private val verifier = PackageVerifier()
    private val installer = RegionInstaller(paths, verifier)
    private val extractor = ZipPackageExtractor(log)

    suspend fun download(
        request: RegionRequest,
        policy: DownloadPolicy,
        access: NetworkAccess,
        cancellation: CancellationHandle = CancellationHandle.None,
    ): Outcome<InstalledRegion> {
        validate(request)?.let { return it }
        return installMutex.withLock {
            access.request(policy.switch) {
                try {
                    perform(request, policy, cancellation)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    log("download failed region=${request.region} type=${exception::class.simpleName}")
                    Outcome.Failure(AppError.Internal(INTERNAL_ID))
                }
            }
        }
    }

    private fun validate(request: RegionRequest): Outcome<InstalledRegion>? {
        if (!RegionIdentifiers.isSafeSegment(request.region) ||
            !RegionIdentifiers.isSafeSegment(request.version)
        ) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        if (request.url.isBlank()) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        return null
    }

    private suspend fun perform(
        request: RegionRequest,
        policy: DownloadPolicy,
        cancellation: CancellationHandle,
    ): Outcome<InstalledRegion> {
        val nonce = UUID.randomUUID().toString()
        val work = paths.newWorkDirectory(request.region, request.version)
        if (!work.mkdirs()) {
            return Outcome.Failure(AppError.Internal(INTERNAL_ID))
        }
        try {
            return downloadInto(request, policy, cancellation, work, nonce)
        } finally {
            work.deleteRecursively()
        }
    }

    private suspend fun downloadInto(
        request: RegionRequest,
        policy: DownloadPolicy,
        cancellation: CancellationHandle,
        work: File,
        nonce: String,
    ): Outcome<InstalledRegion> {
        logStart(request)
        val archive = File(work, PACKAGE_FILE)
        when (val downloaded = downloadArchive(request, policy, cancellation, archive)) {
            is Outcome.Failure -> return downloaded
            is Outcome.Success -> Unit
        }
        val staging = File(work, STAGING_DIRECTORY)
        when (val extracted = extractor.extract(archive, staging, policy, cancellation)) {
            is Outcome.Failure -> return extracted
            is Outcome.Success -> Unit
        }
        val manifestBytes =
            when (val read = readManifest(staging)) {
                is Outcome.Success -> read.value
                is Outcome.Failure -> return read
            }
        val manifest =
            when (val parsed = RegionManifestParser.parse(manifestBytes, request.region, request.version)) {
                is Outcome.Success -> {
                    parsed.value
                }

                is Outcome.Failure -> {
                    log("manifest rejected region=${request.region} version=${request.version} reason=${parsed.error}")
                    return parsed
                }
            }
        when (val verified = verifier.verify(staging, manifest, cancellation)) {
            is Outcome.Failure -> {
                log("verify failed region=${request.region} version=${request.version} reason=${verified.error}")
                return verified
            }

            is Outcome.Success -> {
                Unit
            }
        }
        return when (val installed = installer.install(request, staging, manifest, nonce, cancellation)) {
            is Outcome.Failure -> {
                log("install failed region=${request.region} version=${request.version} reason=${installed.error}")
                installed
            }

            is Outcome.Success -> {
                logManifest(installed.value)
                installed
            }
        }
    }

    private suspend fun downloadArchive(
        request: RegionRequest,
        policy: DownloadPolicy,
        cancellation: CancellationHandle,
        target: File,
    ): Outcome<Unit> {
        val transfer =
            DownloadRequest(
                url = request.url,
                token = request.token,
                policy = policy,
            )
        return try {
            FileOutputStream(target).use { out ->
                val result =
                    transport.get(transfer) { buffer, length ->
                        if (cancellation.isCancelled()) {
                            return@get Outcome.Failure(AppError.Cancelled)
                        }
                        try {
                            out.write(buffer, 0, length)
                        } catch (exception: IOException) {
                            return@get Outcome.Failure(AppError.Internal(INTERNAL_ID))
                        }
                        Outcome.Success(Unit)
                    }
                when (result) {
                    is Outcome.Failure -> {
                        result
                    }

                    is Outcome.Success -> {
                        if (result.value.isSuccess) {
                            out.flush()
                            out.fd.sync()
                            Outcome.Success(Unit)
                        } else {
                            log("server refused region=${request.region} status=${result.value.status}")
                            Outcome.Failure(
                                AppError.Server(
                                    status = result.value.status,
                                    retryAfter = result.value.retryAfterSeconds,
                                ),
                            )
                        }
                    }
                }
            }
        } catch (exception: IOException) {
            Outcome.Failure(AppError.Internal(INTERNAL_ID))
        }
    }

    private fun readManifest(staging: File): Outcome<ByteArray> {
        val file = File(staging, RegionIdentifiers.MANIFEST_FILE)
        if (!file.isFile || file.length() > DownloadPolicy.MAX_MANIFEST_BYTES) {
            return Outcome.Failure(AppError.CorruptData)
        }
        return try {
            Outcome.Success(file.readBytes())
        } catch (exception: IOException) {
            Outcome.Failure(AppError.CorruptData)
        }
    }

    private fun logStart(request: RegionRequest) {
        val location = sanitizedLocation(request.url)
        log(
            "download start region=${request.region} version=${request.version} " +
                "url=$location token=${if (request.token == null) "absent" else "present"}",
        )
    }

    private fun logManifest(installed: InstalledRegion) {
        val manifest = installed.manifest
        val components =
            manifest.components.joinToString(separator = ",") { component ->
                "${component.kind}:${component.format}:${component.formatVersion}:${component.bytes}"
            }
        val sources = manifest.pipeline?.sources?.joinToString(separator = ",") { it.archive } ?: "none"
        val bbox = manifest.pipeline?.bbox?.joinToString(separator = ",") ?: "none"
        log(
            "manifest region=${manifest.region} version=${manifest.version} " +
                "packageFormat=${manifest.packageFormat} osm=${manifest.osmTimestamp} " +
                "bbox=$bbox components=$components sources=$sources bytes=${installed.verifiedBytes}",
        )
        log(
            "installed region=${manifest.region} version=${manifest.version} " +
                "current=${installed.currentVersion} reused=${installed.reusedExisting} " +
                "path=${installed.directory.absolutePath}",
        )
    }

    private fun sanitizedLocation(url: String): String =
        try {
            val uri = URI(url)
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$port${uri.rawPath}"
        } catch (exception: URISyntaxException) {
            "invalid"
        }

    private companion object {
        const val INTERNAL_ID = "region.download"
        const val PACKAGE_FILE = "package.zip"
        const val STAGING_DIRECTORY = "staging"
    }
}
