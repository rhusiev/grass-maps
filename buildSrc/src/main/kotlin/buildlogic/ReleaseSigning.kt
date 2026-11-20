package buildlogic

import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The values release signing reads and the only source it accepts.
 *
 * A keystore path or a password in a tracked file is a leak, and a default
 * password is worse than an unsigned build, so none of the four has a default
 * or a Gradle property. A build that runs without them stays unsigned, which
 * is what CI and the reproducibility job expect.
 */
object ReleaseSigning {
    const val KEYSTORE = "GRASSMAPS_KEYSTORE"
    const val KEYSTORE_PASSWORD = "GRASSMAPS_KEYSTORE_PASSWORD"
    const val KEY_ALIAS = "GRASSMAPS_KEY_ALIAS"
    const val KEY_PASSWORD = "GRASSMAPS_KEY_PASSWORD"

    /** The signing config the release build type takes when all four are set. */
    const val CONFIG_NAME = "release"

    /** The local copy of the passphrase, outside the repository. */
    const val PASSWORD_FILE_NAME = "release-keystore.pass"

    val variableNames: List<String> = listOf(KEYSTORE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)

    /** Whether the environment leaves release signing absent, partial or complete. */
    enum class State {
        ABSENT,
        PARTIAL,
        COMPLETE,
    }

    /** The names among [variableNames] that have no non-blank value in [values]. */
    fun missing(values: Map<String, String?>): List<String> =
        variableNames.filter { values[it].isNullOrBlank() }

    fun state(values: Map<String, String?>): State {
        val missing = missing(values)
        return when {
            missing.size == variableNames.size -> State.ABSENT
            missing.isNotEmpty() -> State.PARTIAL
            else -> State.COMPLETE
        }
    }

    /** The passphrase file in the directory the debug token also uses. */
    fun defaultPasswordFile(home: String): File = File(DebugToken.defaultDirectory(home), PASSWORD_FILE_NAME)
}

/** Extensions whose contents the release-signing guard reads as text. */
internal val signingGuardTextExtensions: Set<String> =
    setOf(
        "bash",
        "csv",
        "env",
        "geojson",
        "gitignore",
        "gradle",
        "json",
        "kt",
        "kts",
        "md",
        "properties",
        "sh",
        "toml",
        "tsv",
        "txt",
        "xml",
        "yaml",
        "yml",
    )

/** A keystore extension is itself a finding: the key must stay outside the repository. */
internal val signingGuardKeystoreExtensions: Set<String> =
    setOf("jks", "keystore", "p12", "pfx")

/** A passphrase file in the repository is a finding, whatever it contains. */
internal val signingGuardPassphraseExtensions: Set<String> = setOf("pass")

/**
 * Text that names the release key or its offline backup. The marker values are
 * built from parts so this file does not trip its own scan, and a finding names
 * the file rather than the line.
 */
internal val signingGuardKeyPathMarkers: List<String> =
    listOf(
        "/hdd/drive/" + "Recover",
        "grassmaps-" + "release.jks",
    )

/** A literal assignment of a store or key password in a Gradle file. */
private val hardcodedPasswordAssignment =
    Regex("""\b(storePassword|keyPassword)\s*[=:]\s*['"]([^'"]*)['"]""")

/** A keystore path written into a Gradle file instead of read from the environment. */
private val literalStoreFileAssignment =
    Regex("""\bstoreFile\s*[=:]\s*(?:file\()?\s*['"]([^'"]+)['"]""")

/** A password property written into a `.properties` or `.env` file. */
private val passwordPropertyAssignment =
    Regex("""(?i)^\s*[A-Za-z0-9_.-]*password\s*[=:]\s*(\S.*)$""")

/**
 * Findings for the state of the release build type. With all four variables
 * set, the release build must take the release config, never the debug one.
 * Without them it must stay unsigned, so an APK that looks distributable is a
 * finding rather than a silent fallback.
 */
internal fun releaseSigningFindings(
    state: ReleaseSigning.State,
    signingConfigName: String?,
    missingVariables: List<String>,
): List<String> =
    buildList {
        when (state) {
            ReleaseSigning.State.PARTIAL ->
                add(
                    "release signing is incomplete: ${missingVariables.joinToString(", ")} " +
                        "not set; set all four variables or none",
                )
            ReleaseSigning.State.COMPLETE ->
                if (signingConfigName != ReleaseSigning.CONFIG_NAME) {
                    add(
                        "the release build type signs with " +
                            "${signingConfigName ?: "no signing config"} while the environment " +
                            "sets the signing variables; expected ${ReleaseSigning.CONFIG_NAME}",
                    )
                }
            ReleaseSigning.State.ABSENT ->
                if (signingConfigName != null) {
                    add(
                        "the release build type signs with $signingConfigName while the signing " +
                            "variables are absent; the release APK must stay unsigned",
                    )
                }
        }
    }

/**
 * Findings for a password or a key path in the repository. A passphrase value
 * is matched byte for byte and never printed; a keystore file, a known key
 * path and a literal password assignment fail even when the value itself is
 * unknown, as it is on CI.
 */
internal fun committedSecretFindings(
    root: File,
    files: Iterable<File>,
    passphrase: String,
): List<String> {
    val findings = mutableListOf<String>()
    val passphraseBytes = passphrase.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
    files
        .filter { it.isFile }
        .sortedBy { it.path }
        .forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            val extension = file.extension.lowercase(Locale.ROOT)
            if (extension in signingGuardKeystoreExtensions) {
                findings += "a keystore file is in the repository: $relative"
            }
            if (extension in signingGuardPassphraseExtensions) {
                findings += "a passphrase file is in the repository: $relative"
            }
            val bytes =
                try {
                    file.readBytes()
                } catch (exception: IOException) {
                    findings += "the file could not be read: $relative"
                    return@forEach
                }
            if (extension in signingGuardTextExtensions) {
                val text = String(bytes, Charsets.UTF_8)
                signingGuardKeyPathMarkers.forEach { marker ->
                    if (text.contains(marker)) {
                        findings += "the release key path appears in $relative"
                    }
                }
                if (extension == "kts" || extension == "gradle") {
                    hardcodedPasswordAssignment.findAll(text).forEach { match ->
                        if (!match.groupValues[2].contains('$')) {
                            findings += "a hardcoded ${match.groupValues[1]} appears in $relative"
                        }
                    }
                    literalStoreFileAssignment.findAll(text).forEach { match ->
                        if (!match.groupValues[1].contains('$')) {
                            findings += "a hardcoded keystore path appears in $relative"
                        }
                    }
                }
                if (extension == "properties" || extension == "env") {
                    text.lineSequence().forEach { line ->
                        if (passwordPropertyAssignment.matches(line)) {
                            findings += "a hardcoded password property appears in $relative"
                        }
                    }
                }
            }
            if (passphraseBytes != null && bytes.contains(passphraseBytes)) {
                findings +=
                    "the release signing passphrase from the local password file appears in $relative"
            }
        }
    return findings.distinct()
}
