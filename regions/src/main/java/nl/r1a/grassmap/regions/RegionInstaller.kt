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
        val reused =
            when (val current = prepareTarget(target, regionDirectory, manifest, request.version, nonce, cancellation)) {
                is Outcome.Success -> current.value
                is Outcome.Failure -> return current
            }
        if (!reused) {
            when (val moved = moveIntoPlace(staging, target)) {
                is Outcome.Failure -> return moved
                is Outcome.Success -> Unit
            }
        }
        return when (val pointer = CurrentPointer.write(regionDirectory, request.version, nonce)) {
            is Outcome.Failure -> {
                pointer
            }

            is Outcome.Success -> {
                Outcome.Success(
                    InstalledRegion(
                        manifest = manifest,
                        directory = target,
                        verifiedBytes = manifest.totalBytes,
                        reusedExisting = reused,
                        currentVersion = CurrentPointer.read(regionDirectory),
                    ),
                )
            }
        }
    }

    private fun prepareTarget(
        target: File,
        regionDirectory: File,
        manifest: RegionManifest,
        version: String,
        nonce: String,
        cancellation: CancellationHandle,
    ): Outcome<Boolean> {
        if (!target.exists()) {
            return Outcome.Success(false)
        }
        val valid = verifier.verify(target, manifest, cancellation)
        if (valid is Outcome.Success) {
            return Outcome.Success(true)
        }
        if (CurrentPointer.read(regionDirectory) == version) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val quarantine = File(paths.incomingDirectory(), "invalid-$version-$nonce")
        val moved = target.renameTo(quarantine)
        if (!moved) {
            return Outcome.Failure(AppError.Internal(INTERNAL_ID))
        }
        quarantine.deleteRecursively()
        return Outcome.Success(false)
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
