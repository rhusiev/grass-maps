package nl.r1a.grassmaps.plugins.raster

/**
 * One online raster tile source (T-0512).
 *
 * The source names a tile URL template and the credit its licence requires.
 * It never carries a cache: the map engine's own tile cache is the one cache,
 * and a request is made only for a tile the viewport asks for.
 */
data class RasterSource(
    val id: String,
    val displayName: String,
    val tileUrlTemplate: String,
    val attributionHtml: String,
    val minZoom: Int,
    val maxZoom: Int,
    val tileSize: Int,
    val offlineDownload: OfflineTileDownload,
    val licence: RasterLicence? = null,
    val estimatedTileBytes: Long = RasterAreaPlan.DEFAULT_TILE_BYTES,
) {
    /** The URL of one tile, or null when the tile is outside this source. */
    fun tileUrl(
        z: Int,
        x: Int,
        y: Int,
    ): String? {
        if (z !in minZoom..maxZoom) {
            return null
        }
        val tilesPerAxis = 1 shl z
        if (x !in 0 until tilesPerAxis || y !in 0 until tilesPerAxis) {
            return null
        }
        return RasterTileTemplate.format(tileUrlTemplate, z, x, y)
    }

    /** Whether [url] is a tile URL this source's template can produce. */
    fun allows(url: String): Boolean = RasterTileTemplate.matches(tileUrlTemplate, url)

    fun isOpenStreetMap(): Boolean = id == RasterPresets.OPEN_STREET_MAP_ID
}

/**
 * The licence a source's imagery comes under (T-0513). The layer shows it
 * wherever it is drawn: [name] and [url] come from the source online and from
 * the pack's own metadata offline. A non-commercial licence is flagged, so the
 * user sees the restriction before a download.
 */
data class RasterLicence(
    val name: String,
    val url: String,
    val isNonCommercial: Boolean,
)

/** Whether a source's terms allow downloading an area for offline use. */
enum class OfflineTileDownload {
    ALLOWED,

    /** Viewing is allowed; an offline bulk download is not (OSM's policy). */
    REFUSED_BY_POLICY,
}

/**
 * The `{z}`/`{x}`/`{y}` part of a raster tile template: formatting one tile,
 * checking a template a user typed, and matching the URLs the engine builds
 * back to the template that produced them.
 */
object RasterTileTemplate {
    private const val ZOOM = "{z}"
    private const val COLUMN = "{x}"
    private const val ROW = "{y}"
    private val PLACEHOLDERS = listOf(ZOOM, COLUMN, ROW)

    fun format(
        template: String,
        z: Int,
        x: Int,
        y: Int,
    ): String =
        template
            .replace(ZOOM, z.toString())
            .replace(COLUMN, x.toString())
            .replace(ROW, y.toString())

    /**
     * Why [template] is not a usable raster template, or null when it is.
     *
     * Only HTTP(S) URLs qualify. Plain HTTP is accepted for a loopback test
     * server and refused for a remote host, so a custom source cannot silently
     * send tiles in clear text.
     */
    fun problem(template: String): String? {
        if (template.isBlank()) {
            return "empty"
        }
        if (template.any { it.isWhitespace() }) {
            return "whitespace"
        }
        if (!template.startsWith("https://") && !template.startsWith("http://")) {
            return "scheme"
        }
        if (PLACEHOLDERS.any { it !in template }) {
            return "placeholders"
        }
        val remaining = PLACEHOLDERS.fold(template) { text, placeholder -> text.replace(placeholder, "1") }
        if ('{' in remaining || '}' in remaining) {
            return "unknown-placeholder"
        }
        val uri = runCatching { java.net.URI(remaining) }.getOrNull() ?: return "url"
        if (uri.host.isNullOrBlank()) {
            return "host"
        }
        if (template.startsWith("http://") && !isLoopback(uri.host)) {
            return "cleartext"
        }
        return null
    }

    /** The template's host, with its placeholders replaced by a plain digit. */
    fun host(template: String): String? {
        val plain = PLACEHOLDERS.fold(template) { text, placeholder -> text.replace(placeholder, "1") }
        return runCatching { java.net.URI(plain).host }.getOrNull()
    }

    /**
     * The template as an anchored regular expression. `{z}` is one or two
     * digits, so `8` does not match `18`; `{x}` and `{y}` are any digits.
     */
    fun matcher(template: String): Regex {
        val pattern = StringBuilder("^")
        var literalStart = 0
        var index = 0
        while (index < template.length) {
            val replacement =
                when {
                    template.startsWith(ZOOM, index) -> "\\d{1,2}"
                    template.startsWith(COLUMN, index) -> "\\d+"
                    template.startsWith(ROW, index) -> "\\d+"
                    else -> null
                }
            if (replacement == null) {
                index += 1
                continue
            }
            pattern.append(Regex.escape(template.substring(literalStart, index)))
            pattern.append(replacement)
            index += 3
            literalStart = index
        }
        pattern.append(Regex.escape(template.substring(literalStart)))
        pattern.append("$")
        return Regex(pattern.toString())
    }

    fun matches(
        template: String,
        url: String,
    ): Boolean = matcher(template).matches(url)

    private fun isLoopback(host: String): Boolean = host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
}
