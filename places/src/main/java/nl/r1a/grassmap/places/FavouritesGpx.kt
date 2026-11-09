package nl.r1a.grassmap.places

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * Reads and writes the OsmAnd favourites GPX 1.1 dialect.
 *
 * Every element name here comes from OsmAnd's published GPX format reference,
 * https://osmand.net/docs/technical/osmand-file-formats/osmand-gpx/
 * ("Waypoints Customization"): a favourite is a `<wpt>`; its group is the
 * `<type>` child; its appearance is `<extensions><osmand:color>`,
 * `<osmand:icon>` and `<osmand:background>`; group defaults live in
 * `<gpx><extensions><osmand:points_groups><group name= color= icon=
 * background=/>`. The documented defaults are colour `red` and background
 * `circle`, with no icon.
 *
 * A document that is not well-formed XML, has another root element or declares
 * a DTD is [AppError.CorruptData]. Unknown extension elements, unknown `<wpt>`
 * children and missing extensions are accepted; a waypoint without usable
 * coordinates or a name is skipped and reported.
 *
 * Coordinates are normalized to the seven decimals the format writes, so a
 * reload of the store and a re-import of an exported file yield the same model.
 */
internal object FavouritesGpx {
    fun read(bytes: ByteArray): Outcome<FavouriteDocument> {
        if (bytes.isEmpty()) {
            return Outcome.Failure(AppError.CorruptData)
        }
        if (declaresDtd(bytes)) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val document =
            try {
                documentBuilder().parse(ByteArrayInputStream(bytes))
            } catch (exception: SAXException) {
                return Outcome.Failure(AppError.CorruptData)
            } catch (exception: IOException) {
                return Outcome.Failure(AppError.CorruptData)
            } catch (exception: ParserConfigurationException) {
                return Outcome.Failure(AppError.CorruptData)
            }
        val root = document.documentElement ?: return Outcome.Failure(AppError.CorruptData)
        if (localName(root.tagName) != ELEMENT_GPX) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val groups = readGroups(root)
        val skipped = mutableListOf<SkippedFavourite>()
        val favourites = uniqueSpecials(readFavourites(root, groups, skipped))
        return Outcome.Success(
            FavouriteDocument(
                snapshot =
                    FavouritesSnapshot(
                        groups = groups,
                        favourites = deriveIds(favourites),
                    ),
                skipped = skipped,
            ),
        )
    }

    fun write(snapshot: FavouritesSnapshot): ByteArray =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"").append(CREATOR).append('"')
            append(" xmlns=\"").append(GPX_NAMESPACE).append('"')
            append(" xmlns:osmand=\"").append(OSMAND_NAMESPACE).append('"')
            append(" xmlns:grassmap=\"").append(GRASSMAP_NAMESPACE).append('"')
            append(" xmlns:xsi=\"").append(XSI_NAMESPACE).append('"')
            append(" xsi:schemaLocation=\"").append(GPX_NAMESPACE).append(' ')
            append(GPX_SCHEMA).append("\">\n")
            append("  <metadata>\n")
            append("    <name>").append(METADATA_NAME).append("</name>\n")
            append("  </metadata>\n")
            for (favourite in snapshot.favourites) {
                appendFavourite(favourite)
            }
            if (snapshot.groups.isNotEmpty()) {
                append("  <extensions>\n")
                append("    <osmand:points_groups>\n")
                for (group in snapshot.groups) {
                    append("      <group name=\"")
                    appendAttribute(group.name)
                    append("\" color=\"")
                    appendAttribute(group.color)
                    append('"')
                    if (group.icon != null) {
                        append(" icon=\"")
                        appendAttribute(group.icon)
                        append('"')
                    }
                    append(" background=\"")
                    appendAttribute(group.shape.wireName)
                    append("\"/>\n")
                }
                append("    </osmand:points_groups>\n")
                append("  </extensions>\n")
            }
            append("</gpx>\n")
        }.toByteArray(Charsets.UTF_8)

    private fun StringBuilder.appendFavourite(favourite: Favourite) {
        append("  <wpt lat=\"").append(coordinate(favourite.latitude))
        append("\" lon=\"").append(coordinate(favourite.longitude)).append("\">\n")
        append("    <name>")
        appendText(favourite.name)
        append("</name>\n")
        if (favourite.description != null) {
            append("    <desc>")
            appendText(favourite.description)
            append("</desc>\n")
        }
        if (favourite.group != null) {
            append("    <type>")
            appendText(favourite.group)
            append("</type>\n")
        }
        append("    <extensions>\n")
        append("      <osmand:color>")
        appendText(favourite.color)
        append("</osmand:color>\n")
        if (favourite.icon != null) {
            append("      <osmand:icon>")
            appendText(favourite.icon)
            append("</osmand:icon>\n")
        }
        append("      <osmand:background>")
        appendText(favourite.shape.wireName)
        append("</osmand:background>\n")
        if (favourite.special != null) {
            append("      <grassmap:special>")
            appendText(favourite.special.wireName)
            append("</grassmap:special>\n")
        }
        append("    </extensions>\n")
        append("  </wpt>\n")
    }

    private fun StringBuilder.appendText(text: String) {
        for (character in text) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\r' -> append("&#13;")
                else -> append(character)
            }
        }
    }

    private fun StringBuilder.appendAttribute(text: String) {
        for (character in text) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\n' -> append("&#10;")
                '\r' -> append("&#13;")
                '\t' -> append("&#9;")
                else -> append(character)
            }
        }
    }

    private fun readGroups(root: Element): List<FavouriteGroup> {
        val groups = mutableListOf<FavouriteGroup>()
        val names = mutableSetOf<String>()
        for (extensions in childElements(root, ELEMENT_EXTENSIONS)) {
            for (pointsGroups in childElements(extensions, ELEMENT_POINTS_GROUPS)) {
                for (element in childElements(pointsGroups, ELEMENT_GROUP)) {
                    val name = attribute(element, ATTRIBUTE_NAME)?.trim().orEmpty()
                    if (name.isEmpty() || !names.add(name)) {
                        continue
                    }
                    groups +=
                        FavouriteGroup(
                            name = name,
                            color =
                                attribute(element, ATTRIBUTE_COLOR)
                                    ?.trim()
                                    .orEmpty()
                                    .ifEmpty { Favourite.DEFAULT_COLOR },
                            icon = attribute(element, ATTRIBUTE_ICON)?.trim()?.takeIf { it.isNotEmpty() },
                            shape = shapeOf(attribute(element, ATTRIBUTE_BACKGROUND)),
                        )
                }
            }
        }
        return groups
    }

    private fun readFavourites(
        root: Element,
        groups: List<FavouriteGroup>,
        skipped: MutableList<SkippedFavourite>,
    ): List<Favourite> {
        val groupsByName = groups.associateBy { it.name }
        val favourites = mutableListOf<Favourite>()
        for ((index, element) in childElements(root, ELEMENT_WAYPOINT).withIndex()) {
            val rawLatitude = coordinateValue(attribute(element, ATTRIBUTE_LATITUDE))
            val rawLongitude = coordinateValue(attribute(element, ATTRIBUTE_LONGITUDE))
            if (rawLatitude == null || rawLongitude == null) {
                skipped += SkippedFavourite(index, FavouriteSkipReason.MissingCoordinates)
                continue
            }
            val latitude = normalizedCoordinate(rawLatitude)
            val longitude = normalizedCoordinate(rawLongitude)
            if (!isWgs84(latitude, longitude)) {
                skipped += SkippedFavourite(index, FavouriteSkipReason.OutOfRangeCoordinates)
                continue
            }
            val name = childText(element, ELEMENT_NAME)?.trim().orEmpty()
            if (name.isEmpty()) {
                skipped += SkippedFavourite(index, FavouriteSkipReason.MissingName)
                continue
            }
            val group = childText(element, ELEMENT_TYPE)?.trim()?.takeIf { it.isNotEmpty() }
            val defaults = group?.let(groupsByName::get)
            val appearance = readAppearance(element)
            favourites +=
                Favourite(
                    id = FavouriteId(EMPTY_ID),
                    latitude = latitude,
                    longitude = longitude,
                    name = name,
                    description = childText(element, ELEMENT_DESCRIPTION)?.trim()?.takeIf { it.isNotEmpty() },
                    group = group,
                    color = appearance.color ?: defaults?.color ?: Favourite.DEFAULT_COLOR,
                    icon = appearance.icon ?: defaults?.icon,
                    shape = appearance.shape ?: defaults?.shape ?: FavouriteShape.Circle,
                    special = readSpecial(element),
                )
        }
        return favourites
    }

    private fun readAppearance(element: Element): Appearance {
        var color: String? = null
        var icon: String? = null
        var pointShape: FavouriteShape? = null
        for (extensions in childElements(element, ELEMENT_EXTENSIONS)) {
            for (child in elementChildren(extensions)) {
                if (!isOsmand(child.tagName)) {
                    continue
                }
                when (localName(child.tagName)) {
                    ELEMENT_COLOR -> {
                        if (color == null) {
                            color = child.textContent?.trim()?.takeIf { it.isNotEmpty() }
                        }
                    }

                    ELEMENT_ICON -> {
                        if (icon == null) {
                            icon = child.textContent?.trim()?.takeIf { it.isNotEmpty() }
                        }
                    }

                    ELEMENT_BACKGROUND -> {
                        if (pointShape == null) {
                            pointShape =
                                child.textContent
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let(::shapeOf)
                        }
                    }

                    else -> {
                    }
                }
            }
        }
        return Appearance(color = color, icon = icon, shape = pointShape)
    }

    /** The Home or Work marker, written in our own namespace beside OsmAnd's. */
    private fun readSpecial(element: Element): FavouriteSpecial? {
        for (extensions in childElements(element, ELEMENT_EXTENSIONS)) {
            for (child in elementChildren(extensions)) {
                if (prefix(child.tagName) != GRASSMAP_PREFIX || localName(child.tagName) != ELEMENT_SPECIAL) {
                    continue
                }
                val name = child.textContent?.trim()?.lowercase(Locale.ROOT) ?: continue
                return FavouriteSpecial.entries.firstOrNull { it.wireName == name }
            }
        }
        return null
    }

    /** A file can name the same special twice; the first record keeps it. */
    private fun uniqueSpecials(favourites: List<Favourite>): List<Favourite> {
        val seen = mutableSetOf<FavouriteSpecial>()
        return favourites.map { favourite ->
            val special = favourite.special
            if (special == null || seen.add(special)) favourite else favourite.copy(special = null)
        }
    }

    private fun shapeOf(text: String?): FavouriteShape {
        val name = text?.trim()?.lowercase(Locale.ROOT) ?: return FavouriteShape.Circle
        return FavouriteShape.entries.firstOrNull { it.wireName == name } ?: FavouriteShape.Circle
    }

    private fun coordinateValue(text: String?): Double? = text?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun normalizedCoordinate(value: Double): Double = coordinate(value).toDouble()

    private fun isWgs84(
        latitude: Double,
        longitude: Double,
    ): Boolean = latitude in MIN_LATITUDE..MAX_LATITUDE && longitude in MIN_LONGITUDE..MAX_LONGITUDE

    /** Element children in document order. GPX 1.1 puts every child in the GPX namespace. */
    private fun elementChildren(parent: Element): List<Element> {
        val children = parent.childNodes
        val result = mutableListOf<Element>()
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element) {
                result += child
            }
        }
        return result
    }

    private fun childElements(
        parent: Element,
        wanted: String,
    ): List<Element> = elementChildren(parent).filter { localName(it.tagName) == wanted }

    private fun childText(
        parent: Element,
        wanted: String,
    ): String? = childElements(parent, wanted).firstOrNull()?.textContent

    private fun attribute(
        element: Element,
        wanted: String,
    ): String? = element.getAttribute(wanted).takeIf { it.isNotEmpty() }

    private fun localName(tagName: String): String = tagName.substringAfterLast(':')

    private fun prefix(tagName: String): String = tagName.substringBefore(':', missingDelimiterValue = "")

    private fun isOsmand(tagName: String): Boolean = prefix(tagName).let { it.isEmpty() || it == OSMAND_PREFIX }

    private fun documentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isExpandEntityReferences = false
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        return factory.newDocumentBuilder()
    }

    private fun setFeature(
        factory: DocumentBuilderFactory,
        name: String,
        enabled: Boolean,
    ) {
        try {
            factory.setFeature(name, enabled)
        } catch (exception: ParserConfigurationException) {
            // The platform parser may not know every hardening feature.
        }
    }

    /**
     * A GPX document never needs a DTD, and the platform parser may ignore
     * `disallow-doctype-decl`. Reject a doctype or entity declaration in the raw
     * bytes before the parser sees them, so an external entity cannot be
     * resolved even when the feature call above is unsupported. The scan also
     * covers the UTF-16 forms, which an ASCII-only scan would miss.
     */
    private fun declaresDtd(bytes: ByteArray): Boolean =
        FORBIDDEN_DECLARATIONS.any { declaration ->
            containsAsciiIgnoreCase(bytes, declaration) ||
                containsUtf16(bytes, declaration, littleEndian = true) ||
                containsUtf16(bytes, declaration, littleEndian = false)
        }

    private fun containsAsciiIgnoreCase(
        bytes: ByteArray,
        marker: String,
    ): Boolean {
        for (start in 0..bytes.size - marker.length) {
            if (matchesAscii(bytes, start, marker)) {
                return true
            }
        }
        return false
    }

    private fun matchesAscii(
        bytes: ByteArray,
        start: Int,
        marker: String,
    ): Boolean {
        for (offset in marker.indices) {
            val value = bytes[start + offset].toInt() and BYTE_MASK
            if (asciiUpper(value) != marker[offset].code) {
                return false
            }
        }
        return true
    }

    private fun containsUtf16(
        bytes: ByteArray,
        marker: String,
        littleEndian: Boolean,
    ): Boolean {
        for (start in 0..bytes.size - marker.length * UTF16_CODE_UNIT_BYTES) {
            if (matchesUtf16(bytes, start, marker, littleEndian)) {
                return true
            }
        }
        return false
    }

    private fun matchesUtf16(
        bytes: ByteArray,
        start: Int,
        marker: String,
        littleEndian: Boolean,
    ): Boolean {
        for (offset in marker.indices) {
            val first = bytes[start + offset * UTF16_CODE_UNIT_BYTES].toInt() and BYTE_MASK
            val second = bytes[start + offset * UTF16_CODE_UNIT_BYTES + 1].toInt() and BYTE_MASK
            val value = if (littleEndian) first or (second shl 8) else (first shl 8) or second
            if (asciiUpper(value) != marker[offset].code) {
                return false
            }
        }
        return true
    }

    private fun asciiUpper(value: Int): Int = if (value in 'a'.code..'z'.code) value - LOWERCASE_TO_UPPERCASE else value

    private data class Appearance(
        val color: String?,
        val icon: String?,
        val shape: FavouriteShape?,
    )

    private val FORBIDDEN_DECLARATIONS = listOf("<!DOCTYPE", "<!ENTITY")

    private const val BYTE_MASK = 0xff
    private const val UTF16_CODE_UNIT_BYTES = 2
    private const val LOWERCASE_TO_UPPERCASE = 'a'.code - 'A'.code

    private const val CREATOR = "Grass Maps"
    private const val METADATA_NAME = "favourites"
    private const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
    private const val GPX_SCHEMA = "http://www.topografix.com/GPX/1/1/gpx.xsd"
    private const val OSMAND_NAMESPACE = "https://osmand.net"
    private const val OSMAND_PREFIX = "osmand"
    private const val GRASSMAP_NAMESPACE = "https://grassmaps.app/gpx/1"
    private const val GRASSMAP_PREFIX = "grassmap"
    private const val XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance"

    private const val ELEMENT_GPX = "gpx"
    private const val ELEMENT_EXTENSIONS = "extensions"
    private const val ELEMENT_POINTS_GROUPS = "points_groups"
    private const val ELEMENT_GROUP = "group"
    private const val ELEMENT_WAYPOINT = "wpt"
    private const val ELEMENT_NAME = "name"
    private const val ELEMENT_DESCRIPTION = "desc"
    private const val ELEMENT_TYPE = "type"
    private const val ELEMENT_COLOR = "color"
    private const val ELEMENT_ICON = "icon"
    private const val ELEMENT_BACKGROUND = "background"
    private const val ELEMENT_SPECIAL = "special"
    private const val ATTRIBUTE_LATITUDE = "lat"
    private const val ATTRIBUTE_LONGITUDE = "lon"
    private const val ATTRIBUTE_NAME = "name"
    private const val ATTRIBUTE_COLOR = "color"
    private const val ATTRIBUTE_ICON = "icon"
    private const val ATTRIBUTE_BACKGROUND = "background"
    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0
}

/** The seven decimals of a GPX coordinate, shared by the writer and the id rule. */
internal fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.7f", value)

/** Derives the content-addressed id base of one favourite. */
internal object FavouriteIds {
    fun base(favourite: Favourite): String {
        val identity =
            listOf(
                coordinate(favourite.latitude),
                coordinate(favourite.longitude),
                favourite.name,
                favourite.description.orEmpty(),
                favourite.group.orEmpty(),
                favourite.color,
                favourite.icon.orEmpty(),
                favourite.shape.wireName,
            ).joinToString(SEPARATOR)
        val digest = MessageDigest.getInstance(SHA_256).digest(identity.toByteArray(Charsets.UTF_8))
        return digest.take(ID_BYTES).joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and BYTE_MASK)
        }
    }

    private const val SHA_256 = "SHA-256"
    private const val ID_BYTES = 8
    private const val BYTE_MASK = 0xff
    private const val SEPARATOR = "\u0001"
}
