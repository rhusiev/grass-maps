package nl.r1a.grassmaps.regions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmaps.plugin.api.contract.CancellationHandle
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A package after verification and installation. */
data class InstalledRegion(
    val manifest: RegionManifest,
    val directory: File,
    val verifiedBytes: Long,
    val reusedExisting: Boolean,
    val currentVersion: String?,
)

/**
 * Moves a verified staging directory into `regions/<region>/<version>` and
 * replaces the region's `current` pointer.
 *
 * The pointer is the commit point. A failure or a cancel before it leaves the
 * pointer on the previous version, which is still present and mounted. The old
 * version directory stays after the flip, because the running process reads its
 * files through readers that open once. [SupersededVersions] deletes it at the
 * next process start. The rename of the version directory and the pointer
 * replace are both atomic on the same filesystem.
 *
 * A current version that fails verification is quarantined under `.incoming`
 * until the verified replacement is in place, and put back if the move fails.
 * A crash between the quarantine and the pointer replace leaves that copy under
 * `.incoming`; the next install restores it before it touches the region, so
 * `current` does not keep naming a directory that only exists in quarantine.
 * The crash window is also described in the README.
 */
internal class RegionInstaller(
    private val paths: RegionPaths,
    private val verifier: PackageVerifier,
    private val move: (File, File) -> Boolean = ::atomicMove,
) {
    fun install(
        request: RegionRequest,
        staging: File,
        manifest: RegionManifest,
        nonce: String,
        cancellation: CancellationHandle,
    ): Outcome<InstalledRegion> {
        val regionDirectory = paths.regionDirectory(request.region)
        if (!regionDirectory.isDirectory && !regionDirectory.mkdirs()) {
            return Outcome.Failure(AppError.Internal(INTERNAL_ID))
        }
        sweepQuarantines()
        val target = paths.versionDirectory(request.region, request.version)
        val prepared =
            prepareTarget(
                region = request.region,
                target = target,
                regionDirectory = regionDirectory,
                manifest = manifest,
                version = request.version,
                nonce = nonce,
                cancellation = cancellation,
            )
        val targetState =
            when (prepared) {
                is Outcome.Success -> prepared.value
                is Outcome.Failure -> return prepared
            }
        if (cancellation.isCancelled()) {
            discardUncommitted(targetState, target)
            return Outcome.Failure(AppError.Cancelled)
        }
        if (!targetState.reused) {
            val moved = moveIntoPlace(staging, target)
            if (moved is Outcome.Failure) {
                val quarantine = targetState.quarantine
                if (quarantine != null && !move(quarantine, target)) {
                    // The pointer still names the target, and the
                    // quarantined copy is now the only one left. Keep it
                    // for the next sweep and report the failed rollback.
                    return Outcome.Failure(AppError.Internal(ROLLBACK_ID))
                }
                return moved
            }
        }
        return when (val pointer = CurrentPointer.write(regionDirectory, request.version, nonce)) {
            is Outcome.Failure -> {
                pointer
            }

            is Outcome.Success -> {
                targetState.quarantine?.deleteRecursively()
                Outcome.Success(
                    InstalledRegion(
                        manifest = manifest,
                        directory = target,
                        verifiedBytes = manifest.totalBytes,
                        reusedExisting = targetState.reused,
                        currentVersion = CurrentPointer.read(regionDirectory),
                    ),
                )
            }
        }
    }

    /**
     * A cancel before the move leaves the pointer alone. A quarantined current
     * version goes back to its place, because [prepareTarget] already moved it.
     */
    private fun discardUncommitted(
        state: Prepared,
        target: File,
    ) {
        // A failed restore is left to the next install's sweep, as after a crash.
        state.quarantine?.let { move(it, target) }
    }

    /** The target's state after verification: reuse it, or clear it for the new copy. */
    private data class Prepared(
        val reused: Boolean,
        val quarantine: File?,
    )

    private data class Quarantine(
        val region: String,
        val version: String,
    )

    /**
     * Restores or deletes every quarantine left by an earlier install. A
     * quarantine whose version the region's `current` still names, and whose
     * target directory is missing, is the copy the pointer expects: put it
     * back. An attributed quarantine that is not needed is deleted. A restore
     * that fails is left for the next attempt, and the install that follows
     * replaces the target anyway.
     */
    private fun sweepQuarantines() {
        val entries =
            try {
                paths.incomingDirectory().listFiles()
            } catch (exception: SecurityException) {
                null
            } ?: return
        for (entry in entries) {
            if (!entry.isDirectory || !entry.name.startsWith(RegionPaths.QUARANTINE_PREFIX)) {
                continue
            }
            val quarantine = quarantineOf(entry) ?: continue
            val regionDirectory = paths.regionDirectory(quarantine.region)
            val target = paths.versionDirectory(quarantine.region, quarantine.version)
            if (regionDirectory.isDirectory &&
                CurrentPointer.read(regionDirectory) == quarantine.version &&
                !target.exists()
            ) {
                move(entry, target)
            } else {
                entry.deleteRecursively()
            }
        }
    }

    /**
     * The region and version a quarantine belongs to. The name carries both for
     * a quarantine this build wrote. An older build wrote only the version, so
     * the package manifest inside is the fallback. A directory that cannot be
     * attributed is left alone rather than deleted: a download's work directory
     * lives under the same root and must not be swept.
     */
    private fun quarantineOf(entry: File): Quarantine? = quarantineName(entry.name) ?: quarantineManifest(entry)

    private fun quarantineName(name: String): Quarantine? {
        if (!name.startsWith("${RegionPaths.QUARANTINE_PREFIX}${RegionPaths.QUARANTINE_SEPARATOR}")) {
            return null
        }
        val parts = name.split(RegionPaths.QUARANTINE_SEPARATOR)
        if (parts.size != QUARANTINE_NAME_PARTS) {
            return null
        }
        val region = parts[1]
        val version = parts[2]
        if (!RegionIdentifiers.isSafeSegment(region) || !RegionIdentifiers.isSafeSegment(version)) {
            return null
        }
        return Quarantine(region, version)
    }

    private fun quarantineManifest(entry: File): Quarantine? {
        if (!entry.name.startsWith("${RegionPaths.QUARANTINE_PREFIX}-")) {
            return null
        }
        val manifest = File(entry, RegionIdentifiers.MANIFEST_FILE)
        if (!manifest.isFile || manifest.length() > MAX_QUARANTINE_MANIFEST_BYTES) {
            return null
        }
        val document =
            try {
                Json.parseToJsonElement(String(manifest.readBytes(), Charsets.UTF_8)) as? JsonObject
            } catch (exception: IOException) {
                null
            } catch (exception: IllegalArgumentException) {
                null
            } catch (exception: SecurityException) {
                null
            } ?: return null
        val region = (document[REGION_FIELD] as? JsonPrimitive)?.contentOrNull ?: return null
        val version = (document[VERSION_FIELD] as? JsonPrimitive)?.contentOrNull ?: return null
        if (!RegionIdentifiers.isSafeSegment(region) || !RegionIdentifiers.isSafeSegment(version)) {
            return null
        }
        return Quarantine(region, version)
    }

    private fun prepareTarget(
        region: String,
        target: File,
        regionDirectory: File,
        manifest: RegionManifest,
        version: String,
        nonce: String,
        cancellation: CancellationHandle,
    ): Outcome<Prepared> {
        if (!target.exists()) {
            return Outcome.Success(Prepared(reused = false, quarantine = null))
        }
        val valid = verifier.verify(target, manifest, cancellation)
        if (valid is Outcome.Success) {
            return Outcome.Success(Prepared(reused = true, quarantine = null))
        }
        if (valid is Outcome.Failure && valid.error == AppError.Cancelled) {
            return valid
        }
        val quarantine = paths.quarantineDirectory(region, version, nonce)
        quarantine.deleteRecursively()
        if (!move(target, quarantine)) {
            return Outcome.Failure(AppError.Internal(INTERNAL_ID))
        }
        // A corrupt non-current version is discarded now. A corrupt current
        // version must survive until its verified replacement is in place, so
        // its quarantine is kept for rollback and deleted after the install.
        if (CurrentPointer.read(regionDirectory) != version) {
            quarantine.deleteRecursively()
            return Outcome.Success(Prepared(reused = false, quarantine = null))
        }
        return Outcome.Success(Prepared(reused = false, quarantine = quarantine))
    }

    private fun moveIntoPlace(
        staging: File,
        target: File,
    ): Outcome<Unit> =
        if (move(staging, target)) {
            Outcome.Success(Unit)
        } else {
            Outcome.Failure(AppError.Internal(INTERNAL_ID))
        }

    private companion object {
        const val INTERNAL_ID = "region.install"
        const val ROLLBACK_ID = "region.install.rollback"
        const val QUARANTINE_NAME_PARTS = 4
        const val REGION_FIELD = "region"
        const val VERSION_FIELD = "version"
        const val MAX_QUARANTINE_MANIFEST_BYTES = 1024L * 1024L
    }
}

private fun atomicMove(
    source: File,
    target: File,
): Boolean =
    try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (exception: IOException) {
        false
    } catch (exception: SecurityException) {
        false
    }
