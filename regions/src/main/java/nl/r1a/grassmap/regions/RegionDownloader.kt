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

/** The stage one download has reached; the screen names it and the store records it. */
enum class DownloadStage {
    /** The archive body is arriving. */
    ARCHIVE,

    /** The archive arrived and its files are being checked and installed. */
    VERIFY,
}

/** How far one download has got. [bytesDone] counts archive bytes only. */
data class DownloadProgress(
    val bytesDone: Long,
    val stage: DownloadStage,
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
        onProgress: (DownloadProgress) -> Unit = {},
        keepPartialOnCancel: () -> Boolean = { false },
    ): Outcome<InstalledRegion> {
        validate(request)?.let { return it }
        return installMutex.withLock {
            access.request(policy.switch) {
                try {
                    perform(request, policy, cancellation, onProgress, keepPartialOnCancel)
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
        onProgress: (DownloadProgress) -> Unit,
        keepPartialOnCancel: () -> Boolean,
    ): Outcome<InstalledRegion> {
        val nonce = UUID.randomUUID().toString()
        val work = paths.newWorkDirectory(request.region, request.version)
        if (!work.mkdirs()) {
            return Outcome.Failure(AppError.Internal(INTERNAL_ID))
        }
        val partial = paths.partialDirectory(request.region, request.version)
        PartialArchives(paths).discardOtherVersions(request.region, request.version)
        var outcome: Outcome<InstalledRegion>? = null
        try {
            return downloadInto(request, policy, cancellation, work, partial, nonce, onProgress)
                .also { outcome = it }
        } finally {
            work.deleteRecursively()
            if (!keepsPartial(outcome, keepPartialOnCancel)) {
                partial.deleteRecursively()
            }
        }
    }

    /**
     * An unfinished archive survives only a failure that says nothing about its
     * bytes: no connection, a server refusal, a write error, or a pause the
     * caller asked to keep. A call that never returned (a kill) leaves it too.
     * Success, a user cancel, and every verdict that the bytes are wrong
     * discard it, so the next attempt starts from zero.
     */
    private fun keepsPartial(
        outcome: Outcome<InstalledRegion>?,
        keepPartialOnCancel: () -> Boolean,
    ): Boolean {
        val failure = (outcome as? Outcome.Failure)?.error ?: return outcome == null
        return when (failure) {
            AppError.Network, is AppError.Server, is AppError.Internal -> true
            AppError.Cancelled -> keepPartialOnCancel()
            else -> false
        }
    }

    private suspend fun downloadInto(
        request: RegionRequest,
        policy: DownloadPolicy,
        cancellation: CancellationHandle,
        work: File,
        partial: File,
        nonce: String,
        onProgress: (DownloadProgress) -> Unit,
    ): Outcome<InstalledRegion> {
        logStart(request)
        val archive = File(partial, PACKAGE_FILE)
        when (val downloaded = downloadArchive(request, policy, cancellation, archive, onProgress)) {
            is Outcome.Failure -> return downloaded
            is Outcome.Success -> Unit
        }
        onProgress(DownloadProgress(bytesDone = archive.length(), stage = DownloadStage.VERIFY))
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

    /**
     * Downloads the archive into [archive], continuing a partial file from an
     * earlier attempt when there is one. A resume that cannot be trusted - a
     * mismatched `206`, a `416` that does not say the file is complete - is
     * redone once from byte 0.
     */
    private suspend fun downloadArchive(
        request: RegionRequest,
        policy: DownloadPolicy,
        cancellation: CancellationHandle,
        archive: File,
        onProgress: (DownloadProgress) -> Unit,
    ): Outcome<Unit> {
        val parent = archive.parentFile
        if (parent == null || !(parent.isDirectory || parent.mkdirs())) {
            return Outcome.Failure(AppError.Internal(INTERNAL_ID))
        }
        var resumeFrom = archive.takeIf { it.isFile }?.length() ?: 0L
        if (resumeFrom > policy.boundedMaxPackageBytes) {
            archive.delete()
            resumeFrom = 0
        }
        val first = fetchArchive(request, policy, cancellation, archive, resumeFrom, onProgress)
        if (first !== ArchiveFetch.RESTART) {
            return first.outcome
        }
        log("resume rejected region=${request.region} offset=$resumeFrom")
        archive.delete()
        return fetchArchive(request, policy, cancellation, archive, 0L, onProgress).outcome
    }

    private suspend fun fetchArchive(
        request: RegionRequest,
        policy: DownloadPolicy,
        cancellation: CancellationHandle,
        target: File,
        resumeFrom: Long,
        onProgress: (DownloadProgress) -> Unit,
    ): ArchiveFetch {
        val transfer =
            DownloadRequest(
                url = request.url,
                token = request.token,
                policy = policy,
                rangeStart = resumeFrom,
            )
        return try {
            FileOutputStream(target, true).use { out ->
                var written = resumeFrom
                val result =
                    transport.get(
                        transfer,
                        object : BodySink {
                            override suspend fun begin(offset: Long): Outcome<Unit> =
                                try {
                                    if (offset != resumeFrom) {
                                        out.channel.truncate(offset)
                                        written = offset
                                    }
                                    Outcome.Success(Unit)
                                } catch (exception: IOException) {
                                    Outcome.Failure(AppError.Internal(INTERNAL_ID))
                                }

                            override suspend fun write(
                                buffer: ByteArray,
                                length: Int,
                            ): Outcome<Unit> {
                                if (cancellation.isCancelled()) {
                                    return Outcome.Failure(AppError.Cancelled)
                                }
                                try {
                                    out.write(buffer, 0, length)
                                } catch (exception: IOException) {
                                    return Outcome.Failure(AppError.Internal(INTERNAL_ID))
                                }
                                written += length
                                onProgress(DownloadProgress(bytesDone = written, stage = DownloadStage.ARCHIVE))
                                return Outcome.Success(Unit)
                            }
                        },
                    )
                archiveFetchOf(request, resumeFrom, result, out)
            }
        } catch (exception: IOException) {
            ArchiveFetch.failed(AppError.Internal(INTERNAL_ID))
        }
    }

    private fun archiveFetchOf(
        request: RegionRequest,
        resumeFrom: Long,
        result: Outcome<TransportResult>,
        out: FileOutputStream,
    ): ArchiveFetch =
        when (result) {
            is Outcome.Failure -> {
                // A 206 that does not match the file on disk is the only
                // CorruptData a resumed request can get before any byte is written.
                if (resumeFrom > 0 && result.error == AppError.CorruptData) {
                    ArchiveFetch.RESTART
                } else {
                    ArchiveFetch.failed(result.error)
                }
            }

            is Outcome.Success -> {
                val status = result.value
                when {
                    status.isSuccess -> {
                        out.flush()
                        out.fd.sync()
                        ArchiveFetch.COMPLETE
                    }

                    resumeFrom > 0 && status.status == HTTP_RANGE_NOT_SATISFIABLE -> {
                        // A server that says the file is exactly what is on disk
                        // has nothing more to send; any other 416 is a stale partial.
                        if (status.totalBytes == resumeFrom) ArchiveFetch.COMPLETE else ArchiveFetch.RESTART
                    }

                    else -> {
                        log("server refused region=${request.region} status=${status.status}")
                        ArchiveFetch.failed(AppError.Server(status = status.status, retryAfter = status.retryAfterSeconds))
                    }
                }
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
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val INTERNAL_ID = "region.download"
        const val PACKAGE_FILE = "package.zip"
        const val STAGING_DIRECTORY = "staging"
    }
}

/** How one attempt to fetch the archive ended. */
private class ArchiveFetch private constructor(
    val outcome: Outcome<Unit>,
) {
    companion object {
        val COMPLETE = ArchiveFetch(Outcome.Success(Unit))

        /** The partial file is not usable; fetch again from byte 0. */
        val RESTART = ArchiveFetch(Outcome.Failure(AppError.CorruptData))

        fun failed(error: AppError): ArchiveFetch = ArchiveFetch(Outcome.Failure(error))
    }
}
