package buildlogic

import java.io.File
import java.io.IOException
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when the release build type signs with the debug config while the
 * environment supplies the release key, or when a release password, keystore
 * file or key path is committed.
 *
 * The signing state is passed in from the build script, so the check reads the
 * config the release build type actually has. The passphrase file is optional:
 * CI has no copy, and its structural checks still run there.
 */
abstract class CheckReleaseSigningTask : DefaultTask() {
    /** One of [ReleaseSigning.State]'s names. */
    @get:Input
    abstract val signingState: Property<String>

    /** The signing config the release build type takes, or absent when it stays unsigned. */
    @get:Input
    @get:Optional
    abstract val signingConfigName: Property<String>

    /** The environment variables the build found missing. */
    @get:Input
    abstract val missingVariables: ListProperty<String>

    /** Every repository file the secret scan reads. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scanFiles: ConfigurableFileCollection

    /** The directory findings are reported relative to. */
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    /** The local passphrase copy, when this machine has one. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val passphraseFile: RegularFileProperty

    @TaskAction
    fun check() {
        val state = ReleaseSigning.State.valueOf(signingState.get())
        val findings =
            releaseSigningFindings(state, signingConfigName.orNull, missingVariables.get()) +
                committedSecretFindings(
                    rootDirectory.get().asFile,
                    scanFiles.files,
                    readPassphrase(passphraseFile.orNull?.asFile),
                )
        if (findings.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Release signing guard failed:")
                    findings.forEach { appendLine("  $it") }
                    append(
                        "Release signing values come from the environment only. A password, a " +
                            "keystore file or a key path must stay out of the repository, and a " +
                            "release must never take the debug key.",
                    )
                },
            )
        }
        println(
            when (state) {
                ReleaseSigning.State.COMPLETE ->
                    "Release signing OK: the release build type uses the " +
                        "${ReleaseSigning.CONFIG_NAME} config from the environment."
                else -> "Release signing OK: no signing variables, the release build stays unsigned."
            },
        )
    }

    private fun readPassphrase(file: File?): String {
        if (file == null || !file.isFile) {
            return ""
        }
        return try {
            file.readText().trimEnd('\n', '\r')
        } catch (exception: IOException) {
            ""
        } catch (exception: SecurityException) {
            ""
        }
    }
}
