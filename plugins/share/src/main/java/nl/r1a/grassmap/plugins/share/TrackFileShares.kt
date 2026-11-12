package nl.r1a.grassmap.plugins.share

/**
 * The names and MIME type a track export carries (T-0410).
 *
 * The library's selection UI (T-0414) hands the chosen tracks here before the
 * app writes the file. A track's name becomes the file name; a name with a path
 * separator, a reserved character or an unusable length is repaired instead of
 * reaching the system picker. A list has one fixed name, because there is no
 * single track to name it after.
 */
object TrackFileShares {
    const val EXTENSION: String = "gpx"
    const val GPX_MIME_TYPE: String = "application/gpx+xml"

    /** `grass-maps-tracks.gpx` for a list; the track's name, or a stable fallback, for one. */
    fun fileName(
        name: String?,
        id: String?,
        count: Int,
    ): String {
        if (count != 1) {
            return "$LIST_NAME.$EXTENSION"
        }
        val base = cleanName(name) ?: fallbackName(id)
        return "$base.$EXTENSION"
    }

    /**
     * The extension-specific MIME type when the provider offers it, otherwise
     * the provider's first type, so a provider that offers only `application/xml`
     * still gets a usable picker filter.
     */
    fun mimeType(offered: Set<String>): String =
        offered.firstOrNull { type -> type.equals(GPX_MIME_TYPE, ignoreCase = true) }
            ?: offered.firstOrNull { type -> type.startsWith("application/") }
            ?: GPX_MIME_TYPE

    private fun fallbackName(id: String?): String {
        val hash = id?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() }
        return hash?.let { value -> "track-${value.take(ID_PREFIX)}" } ?: FALLBACK_NAME
    }

    private fun cleanName(name: String?): String? {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withoutExtension =
            trimmed
                .removeSuffix(".gpx")
                .removeSuffix(".GPX")
                .trim()
                .takeIf { it.isNotEmpty() } ?: return null
        val repaired =
            buildString {
                for (character in withoutExtension) {
                    when {
                        character.code < FIRST_PRINTABLE -> append('-')
                        character in RESERVED -> append('-')
                        else -> append(character)
                    }
                }
            }.trim()
                .trim('.', ' ', '-')
                .let { name -> takeCodePoints(name, MAX_NAME_LENGTH) }
                .trim()
                .trim('.', ' ', '-')
        return repaired.takeIf { it.isNotEmpty() }
    }

    private fun takeCodePoints(
        text: String,
        max: Int,
    ): String {
        if (text.codePointCount(0, text.length) <= max) {
            return text
        }
        return text.substring(0, text.offsetByCodePoints(0, max))
    }

    private const val LIST_NAME = "grass-maps-tracks"
    private const val FALLBACK_NAME = "track"
    private const val ID_PREFIX = 8
    private const val MAX_NAME_LENGTH = 80
    private const val FIRST_PRINTABLE = 0x20
    private val RESERVED = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
}
