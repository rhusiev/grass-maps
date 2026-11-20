package buildlogic

import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** One byte sequence a release APK must not contain, with the name a finding reports. */
internal data class ForbiddenApkMarker(
    val label: String,
    val bytes: ByteArray,
)

/**
 * The debug-control and development-tools markers a release APK must not carry.
 * The package names and class descriptors are the ones the debug source set and
 * its debug-only dependencies compile, and `ai.control` is the switch every
 * development-tools command requires. A release build has none of them.
 */
internal val debugOnlyApkMarkers: List<ForbiddenApkMarker> =
    listOf(
        "the debug control channel" to "nl/r1a/grassmap/control/",
        "the debug control package" to "nl.r1a.grassmap.control",
        "the development-tools harness" to "nl/r1a/grassmap/devtools/",
        "the development-tools package" to "nl.r1a.grassmap.devtools",
        "the development-tools plugin" to "nl/r1a/grassmap/plugins/dev/tools/",
        "the development-tools plugin id" to "dev.tools",
        "the AI-control switch" to "ai.control",
        "the debug application" to "DebugApplication",
        "the debug token field" to DebugToken.FIELD_NAME,
    ).map { (label, value) -> ForbiddenApkMarker(label, value.toByteArray(Charsets.UTF_8)) }

/** A shorter prefix is not distinctive; a token at or below this length is matched whole. */
private const val TOKEN_PREFIX_LENGTH = 12

/**
 * One marker for every token file: the whole value and a prefix of it. The
 * label names only the file, because a finding goes into a build log.
 */
internal fun tokenMarkers(tokenFiles: Iterable<File>): List<ForbiddenApkMarker> =
    tokenFiles
        .filter { it.isFile && it.name.endsWith(".token") }
        .sortedBy { it.name }
        .flatMap { file ->
            val token = DebugToken.read(file)
            if (token.isEmpty()) {
                emptyList()
            } else {
                val full =
                    ForbiddenApkMarker(
                        "the token from ${file.name}",
                        token.toByteArray(Charsets.UTF_8),
                    )
                val prefix = token.take(TOKEN_PREFIX_LENGTH)
                if (prefix.length < token.length) {
                    listOf(
                        full,
                        ForbiddenApkMarker(
                            "a prefix of the token from ${file.name}",
                            prefix.toByteArray(Charsets.UTF_8),
                        ),
                    )
                } else {
                    listOf(full)
                }
            }
        }

/** Every marker found in any entry of [apk], named with its entry. */
internal fun apkMarkerFindings(
    apk: File,
    markers: List<ForbiddenApkMarker>,
): List<String> {
    val findings = mutableListOf<String>()
    ZipFile(apk).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) {
                continue
            }
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            markers.forEach { marker ->
                if (bytes.contains(marker.bytes)) {
                    findings += "${marker.label} appears in ${entry.name}"
                }
            }
        }
    }
    return findings.distinct()
}

/**
 * Fails when a release APK carries the debug control channel, the development
 * tools or the embedded region token. The token check reads every `.token` file
 * from the directory the build already uses, so a leaked value fails here
 * without the log ever naming it.
 */
abstract class CheckReleaseApkGuardsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apk: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val tokenFiles: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val apkFile = apk.get().asFile
        if (!apkFile.isFile) {
            throw GradleException("Release APK not found: ${apkFile.path}")
        }
        val tokens = tokenFiles.files.sortedBy { it.name }
        val findings = apkMarkerFindings(apkFile, debugOnlyApkMarkers + tokenMarkers(tokens))
        if (findings.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(
                        "Release APK ${apkFile.path} carries debug-only control or a region token:",
                    )
                    findings.forEach { appendLine("  $it") }
                    append(
                        "The control channel, the development tools and the embedded token are " +
                            "debug-only; they must not reach a release build.",
                    )
                },
            )
        }
        println(
            "Release APK OK: no debug control code and no region token from " +
                "${tokens.size} token file(s) in ${apkFile.name}",
        )
    }
}
