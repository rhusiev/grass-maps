package nl.r1a.grassmap.plugins.route.relations

/** One sprite glyph and the colour its SDF sheet is tinted with. */
data class OsmcIcon(
    val image: String,
    val color: String,
)

/**
 * The drawable part of an `osmc:symbol` value (T-0418).
 *
 * The tag is `waycolor:background[:foreground[:foreground2][:text:textcolor]]`.
 * The way colour is the band the network already supplies; this renderer draws
 * the background plate and the main foreground shape. `foreground2` and the
 * text are parsed but not drawn yet. A shape this layer does not carry returns
 * null: the route still draws its band, without inventing a mark.
 */
data class OsmcSymbol(
    val background: OsmcIcon,
    val foreground: OsmcIcon,
)

object OsmcSymbols {
    /** The plate and shape images shipped in the Standard sprite. */
    private val BACKGROUNDS =
        mapOf(
            "square" to "osmc_bg_square",
            "round" to "osmc_bg_round",
            "circle" to "osmc_bg_circle",
            "frame" to "osmc_bg_frame",
        )

    private val FOREGROUNDS =
        mapOf(
            "bar" to "osmc_fg_bar",
            "corner" to "osmc_fg_corner",
            "backslash" to "osmc_fg_backslash",
            "slash" to "osmc_fg_slash",
            "diamond" to "osmc_fg_diamond",
            "dot" to "osmc_fg_dot",
            "circle" to "osmc_bg_circle",
            "cross" to "osmc_fg_cross",
            "x" to "osmc_fg_x",
            "triangle" to "osmc_fg_triangle",
            "triangle_turned" to "osmc_fg_triangle_turned",
            "stripe" to "osmc_fg_stripe",
            "lower" to "osmc_fg_lower",
            "rectangle" to "osmc_fg_rectangle",
            "right" to "osmc_fg_right",
        )

    private val COLORS =
        mapOf(
            "black" to "#000000",
            "blue" to "#0050d2",
            "brown" to "#7a4a1e",
            "gray" to "#808080",
            "grey" to "#808080",
            "green" to "#1f8f3a",
            "orange" to "#e07000",
            "purple" to "#7a2fa0",
            "red" to "#d02c2c",
            "white" to "#ffffff",
            "yellow" to "#f0c800",
        )

    /**
     * Parses [value], or returns null when it names a shape this layer does
     * not draw. An empty background is the common white plate.
     */
    fun parse(value: String?): OsmcSymbol? {
        val parts = value?.split(":") ?: return null
        if (parts.size < 3) {
            return null
        }
        val background = background(parts[1]) ?: return null
        val foreground = foreground(parts[2]) ?: return null
        return OsmcSymbol(background = background, foreground = foreground)
    }

    private fun background(part: String): OsmcIcon? {
        if (part.isEmpty()) {
            return OsmcIcon(image = BACKGROUNDS.getValue("square"), color = COLORS.getValue("white"))
        }
        val tokens = part.split("_")
        val color = COLORS[tokens.first()] ?: return null
        val shape = tokens.drop(1).joinToString("_").ifEmpty { "square" }
        val image = BACKGROUNDS[shape] ?: BACKGROUNDS.getValue("square")
        return OsmcIcon(image = image, color = color)
    }

    private fun foreground(part: String): OsmcIcon? {
        val separator = part.indexOf('_')
        if (separator <= 0 || separator == part.lastIndex) {
            return null
        }
        val color = COLORS[part.substring(0, separator)] ?: return null
        val shape = part.substring(separator + 1)
        val image = FOREGROUNDS[shape] ?: return null
        return OsmcIcon(image = image, color = color)
    }
}
