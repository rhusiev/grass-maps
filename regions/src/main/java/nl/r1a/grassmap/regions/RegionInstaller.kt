package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
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
 * The pointer is the commit point. A failure before it leaves the pointer on
 * the previous version, which is still present. A failure after it leaves the
 * new version current and the old version as an unused directory. The rename
 * of the version directory and the pointer replace are both atomic on the same
 * filesystem; the crash window between them is described in the README.
 */
internal class RegionInstaller(
    private val paths: RegionPaths,
    private val verifier: PackageVerifier,
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
        val target = paths.versionDirectory(request.region, request.version)
        val prepared =
            when (val current = prepareTarget(target, regionDirectory, manifest, request.version, nonce, cancellation)) {
                is Outcome.Success -> current.value
                is Outcome.Failure -> return current
            }
        if (!prepared.reused) {
            when (val moved = moveIntoPlace(staging, target)) {
                is Outcome.Failure -> {
                    // Put a quarantined current version back: the pointer still
                    // names it, and a failed install must leave a mountable region.
                    prepared.quarantine?.renameTo(target)
                    return moved
                }

                is Outcome.Success -> Unit
            }
        }
        return when (val pointer = CurrentPointer.write(regionDirectory, request.version, nonce)) {
            is Outcome.Failure -> {
                pointer
            }

            is Outcome.Success -> {
                prepared.quarantine?.deleteRecursively()
                Outcome.Success(
                    InstalledRegion(
                        manifest = manifest,
                        directory = target,
                        verifiedBytes = manifest.totalBytes,
                        reusedExisting = prepared.reused,
                        currentVersion = CurrentPointer.read(regionDirectory),
                    ),
                )
            }
        }
    }

    /** The target's state after verification: reuse it, or clear it for the new copy. */
    private data class Prepared(
        val reused: Boolean,
        val quarantine: File?,
    )

    private fun prepareTarget(
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
        val quarantine = File(paths.incomingDirectory(), "invalid-$version-$nonce")
        quarantine.deleteRecursively()
        val moved = target.renameTo(quarantine)
        if (!moved) {
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
        try {
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            Outcome.Success(Unit)
        } catch (exception: IOException) {
            Outcome.Failure(AppError.Internal(INTERNAL_ID))
        } catch (exception: SecurityException) {
            Outcome.Failure(AppError.Internal(INTERNAL_ID))
        }

    private companion object {
        const val INTERNAL_ID = "region.install"
    }
}
