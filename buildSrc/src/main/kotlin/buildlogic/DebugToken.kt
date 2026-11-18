package buildlogic

import java.io.File
import java.io.IOException

/**
 * Reads the region-server token a debug build embeds (never a release build).
 *
 * The token lives in a local file outside the repository. A missing,
 * unreadable or blank file yields an empty token, so a build without the file,
 * such as CI, still succeeds and the app falls back to the user's own token.
 */
object DebugToken {
    /** The Gradle property that overrides [defaultFile]. */
    const val FILE_PROPERTY = "grassmaps.debugTokenFile"

    /** The BuildConfig field the debug build type carries. */
    const val FIELD_NAME = "GRASSMAPS_DEBUG_TOKEN"

    private const val DEFAULT_RELATIVE_PATH = ".local/share/grassmaps/debug-builds.token"

    fun defaultFile(home: String): File = File(home, DEFAULT_RELATIVE_PATH)

    /** The first non-blank line of [file], trimmed, or an empty string. */
    fun read(file: File): String =
        try {
            if (file.isFile) parse(file.readText()) else ""
        } catch (exception: IOException) {
            ""
        } catch (exception: SecurityException) {
            ""
        }

    /** The first non-blank line of [text], trimmed, or an empty string. */
    fun parse(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /** [value] as a Java string literal for a BuildConfig field. */
    fun javaLiteral(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
