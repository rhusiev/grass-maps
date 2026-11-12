package nl.r1a.grassmap.plugins.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A strict structural check of the GPX 1.1 schema this repository can run
 * offline.
 *
 * The official `gpx.xsd` is not in the repository. It carries no licence grant
 * on topografix.com, so vendoring it would be a user decision under
 * `AGENTS.md`'s licence rule. This object instead encodes the schema's
 * sequence, cardinality, namespace and simple-type rules. It is deliberately
 * stricter than `processContents="lax"`: an unknown `grassmap` extension fails,
 * so a typo cannot pass as an extension.
 */
internal object GpxSchema11 {
    const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
    const val GRASSMAP_NAMESPACE = "https://grassmaps.app/gpx/1"

    fun assertValid(bytes: ByteArray): Document {
        val document = parse(bytes)
        val root = document.documentElement
        assertNotNull("no document element", root)
        assertEquals("gpx", root.localName)
        assertEquals(GPX_NAMESPACE, root.namespaceURI)
        assertEquals("1.1", root.getAttribute("version"))
        assertTrue("creator is blank", root.getAttribute("creator").isNotBlank())
        assertEquals(
            "$GPX_NAMESPACE http://www.topografix.com/GPX/1/1/gpx.xsd",
            root.getAttributeNS(XSI_NAMESPACE, "schemaLocation"),
        )
        assertAttributes(root, setOf("version", "creator"))
        checkRootSequence(root)
        return document
    }

    private fun parse(bytes: ByteArray): Document =
        try {
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(bytes))
        } catch (exception: Exception) {
            fail("not well-formed XML: ${exception.message}")
            throw AssertionError(exception)
        }

    private fun checkRootSequence(root: Element) {
        val children = childElements(root)
        var index = 0
        index = expectAtMostOne(children, index, "metadata", ::checkMetadata)
        index = expectMany(children, index, "wpt", ::checkWaypoint)
        index = expectMany(children, index, "rte", ::checkRoute)
        index = expectMany(children, index, "trk", ::checkTrack)
        index = expectAtMostOne(children, index, "extensions", ::checkExtensions)
        assertEquals("unexpected element after the GPX sequence", children.size, index)
    }

    private fun checkMetadata(metadata: Element) {
        sequence(
            metadata,
            listOf(
                Step("name", 0, 1),
                Step("desc", 0, 1),
                Step("author", 0, 1),
                Step("copyright", 0, 1),
                Step("link", 0, Int.MAX_VALUE),
                Step("time", 0, 1),
                Step("keywords", 0, 1),
                Step("bounds", 0, 1),
                Step("extensions", 0, 1, ::checkExtensions),
            ),
        )
        childElement(metadata, "time")?.let { time -> assertDateTime(time.textContent) }
        childElement(metadata, "bounds")?.let(::checkBounds)
    }

    private fun checkWaypoint(waypoint: Element) {
        checkPointAttributes(waypoint)
        sequence(
            waypoint,
            listOf(
                Step("ele", 0, 1, ::checkDecimalText),
                Step("time", 0, 1, ::checkDateTimeElement),
                Step("magvar", 0, 1, ::checkDecimalText),
                Step("geoidheight", 0, 1, ::checkDecimalText),
                Step("name", 0, 1),
                Step("cmt", 0, 1),
                Step("desc", 0, 1),
                Step("src", 0, 1),
                Step("link", 0, Int.MAX_VALUE, ::checkLink),
                Step("sym", 0, 1),
                Step("type", 0, 1),
                Step("fix", 0, 1),
                Step("sat", 0, 1, ::checkNonNegativeInteger),
                Step("hdop", 0, 1, ::checkDecimalText),
                Step("vdop", 0, 1, ::checkDecimalText),
                Step("pdop", 0, 1, ::checkDecimalText),
                Step("ageofdgpsdata", 0, 1, ::checkDecimalText),
                Step("dgpsid", 0, 1, ::checkDgpsStation),
                Step("extensions", 0, 1, ::checkExtensions),
            ),
        )
    }

    private fun checkRoute(route: Element) {
        sequence(
            route,
            listOf(
                Step("name", 0, 1),
                Step("cmt", 0, 1),
                Step("desc", 0, 1),
                Step("src", 0, 1),
                Step("link", 0, Int.MAX_VALUE, ::checkLink),
                Step("number", 0, 1, ::checkNonNegativeInteger),
                Step("type", 0, 1),
                Step("extensions", 0, 1, ::checkExtensions),
                Step("rtept", 0, Int.MAX_VALUE, ::checkRoutePoint),
            ),
        )
    }

    private fun checkRoutePoint(point: Element) {
        checkPointAttributes(point)
        sequence(
            point,
            listOf(
                Step("ele", 0, 1, ::checkDecimalText),
                Step("time", 0, 1, ::checkDateTimeElement),
                Step("magvar", 0, 1, ::checkDecimalText),
                Step("geoidheight", 0, 1, ::checkDecimalText),
                Step("name", 0, 1),
                Step("cmt", 0, 1),
                Step("desc", 0, 1),
                Step("src", 0, 1),
                Step("link", 0, Int.MAX_VALUE, ::checkLink),
                Step("sym", 0, 1),
                Step("type", 0, 1),
                Step("fix", 0, 1),
                Step("sat", 0, 1, ::checkNonNegativeInteger),
                Step("hdop", 0, 1, ::checkDecimalText),
                Step("vdop", 0, 1, ::checkDecimalText),
                Step("pdop", 0, 1, ::checkDecimalText),
                Step("ageofdgpsdata", 0, 1, ::checkDecimalText),
                Step("dgpsid", 0, 1, ::checkDgpsStation),
                Step("extensions", 0, 1, ::checkExtensions),
            ),
        )
    }

    private fun checkTrack(track: Element) {
        sequence(
            track,
            listOf(
                Step("name", 0, 1),
                Step("cmt", 0, 1),
                Step("desc", 0, 1),
                Step("src", 0, 1),
                Step("link", 0, Int.MAX_VALUE, ::checkLink),
                Step("number", 0, 1, ::checkNonNegativeInteger),
                Step("type", 0, 1),
                Step("extensions", 0, 1, ::checkExtensions),
                Step("trkseg", 0, Int.MAX_VALUE, ::checkSegment),
            ),
        )
    }

    private fun checkSegment(segment: Element) {
        sequence(
            segment,
            listOf(
                Step("trkpt", 0, Int.MAX_VALUE, ::checkTrackPoint),
                Step("extensions", 0, 1, ::checkExtensions),
            ),
        )
    }

    private fun checkTrackPoint(point: Element) {
        checkPointAttributes(point)
        sequence(
            point,
            listOf(
                Step("ele", 0, 1, ::checkDecimalText),
                Step("time", 0, 1, ::checkDateTimeElement),
                Step("magvar", 0, 1, ::checkDecimalText),
                Step("geoidheight", 0, 1, ::checkDecimalText),
                Step("name", 0, 1),
                Step("cmt", 0, 1),
                Step("desc", 0, 1),
                Step("src", 0, 1),
                Step("link", 0, Int.MAX_VALUE, ::checkLink),
                Step("sym", 0, 1),
                Step("type", 0, 1),
                Step("fix", 0, 1),
                Step("sat", 0, 1, ::checkNonNegativeInteger),
                Step("hdop", 0, 1, ::checkDecimalText),
                Step("vdop", 0, 1, ::checkDecimalText),
                Step("pdop", 0, 1, ::checkDecimalText),
                Step("ageofdgpsdata", 0, 1, ::checkDecimalText),
                Step("dgpsid", 0, 1, ::checkDgpsStation),
                Step("extensions", 0, 1, ::checkExtensions),
            ),
        )
    }

    private fun checkPointAttributes(point: Element) {
        assertAttributes(point, setOf("lat", "lon"))
        val latitude = decimalAttribute(point, "lat")
        val longitude = decimalAttribute(point, "lon")
        assertTrue("latitude $latitude outside -90..90", latitude >= MIN_LATITUDE && latitude <= MAX_LATITUDE)
        assertTrue("longitude $longitude outside -180..180", longitude >= MIN_LONGITUDE && longitude < MAX_LONGITUDE)
    }

    private fun checkExtensions(extensions: Element) {
        assertAttributes(extensions, emptySet())
        for (child in childElements(extensions)) {
            assertEquals(
                "extension ${child.localName} is not in a foreign namespace",
                GRASSMAP_NAMESPACE,
                child.namespaceURI,
            )
            when (child.localName) {
                "colour" -> {
                    assertTrue(
                        "bad grassmap colour '${child.textContent}'",
                        COLOUR.matches(child.textContent),
                    )
                }

                "widthDp" -> {
                    assertDecimal(child.textContent)
                    assertTrue("width is not drawable", child.textContent.toDouble() > 0.0)
                }

                else -> {
                    fail("unknown grassmap extension '${child.localName}'")
                }
            }
            assertEquals("extension ${child.localName} has an attribute", 0, child.attributes.length)
            assertTrue(
                "extension ${child.localName} has element children",
                childElements(child).isEmpty(),
            )
        }
    }

    private fun checkLink(link: Element) {
        assertAttributes(link, setOf("href"))
        assertTrue("link has no href", link.getAttribute("href").isNotBlank())
        sequence(
            link,
            listOf(
                Step("text", 0, 1),
                Step("type", 0, 1),
            ),
        )
    }

    private fun checkBounds(bounds: Element) {
        assertAttributes(bounds, setOf("minlat", "minlon", "maxlat", "maxlon"))
        val minLatitude = decimalAttribute(bounds, "minlat")
        val maxLatitude = decimalAttribute(bounds, "maxlat")
        val minLongitude = decimalAttribute(bounds, "minlon")
        val maxLongitude = decimalAttribute(bounds, "maxlon")
        assertTrue(minLatitude in MIN_LATITUDE..MAX_LATITUDE)
        assertTrue(maxLatitude in MIN_LATITUDE..MAX_LATITUDE)
        assertTrue(minLongitude in MIN_LONGITUDE..MAX_LONGITUDE)
        assertTrue(maxLongitude >= MIN_LONGITUDE && maxLongitude < MAX_LONGITUDE)
    }

    private fun checkDecimalText(element: Element) {
        assertDecimal(element.textContent)
    }

    private fun assertDecimal(text: String) {
        assertTrue("'$text' is not xsd:decimal", DECIMAL.matches(text.trim()))
    }

    private fun checkDateTimeElement(element: Element) {
        assertDateTime(element.textContent)
    }

    private fun assertDateTime(text: String) {
        try {
            OffsetDateTime.parse(text.trim())
        } catch (exception: DateTimeParseException) {
            fail("'$text' is not xsd:dateTime: ${exception.message}")
        }
    }

    private fun checkNonNegativeInteger(element: Element) {
        val value = element.textContent.trim().toIntOrNull()
        assertTrue("'${element.textContent}' is not a non-negative integer", value != null && value >= 0)
    }

    private fun checkDgpsStation(element: Element) {
        val value = element.textContent.trim().toIntOrNull()
        assertTrue("'${element.textContent}' is not a DGPS station", value != null && value in 0..1023)
    }

    private fun assertAttributes(
        element: Element,
        allowedLocalNames: Set<String>,
    ) {
        // xsi:schemaLocation belongs to the XML Schema instance namespace and is
        // allowed by the schema's foreign-attribute rule.
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if (attribute.namespaceURI == XMLNS_NAMESPACE || attribute.namespaceURI == XSI_NAMESPACE) {
                continue
            }
            assertTrue(
                "${element.localName} has unexpected attribute ${attribute.localName}",
                attribute.localName in allowedLocalNames,
            )
            assertEquals(
                "${element.localName} attribute ${attribute.localName} is namespaced",
                null,
                attribute.namespaceURI,
            )
        }
        for (name in allowedLocalNames) {
            assertTrue("${element.localName} misses $name", element.hasAttribute(name))
        }
    }

    private fun decimalAttribute(
        element: Element,
        name: String,
    ): Double {
        val text = element.getAttribute(name)
        assertDecimal(text)
        return text.toDouble()
    }

    private fun sequence(
        parent: Element,
        steps: List<Step>,
    ) {
        val children = childElements(parent)
        var index = 0
        for (step in steps) {
            var count = 0
            while (index < children.size && children[index].localName == step.name) {
                val child = children[index]
                assertEquals(
                    "${parent.localName}/${child.localName} is in the wrong namespace",
                    GPX_NAMESPACE,
                    child.namespaceURI,
                )
                step.check?.invoke(child)
                index += 1
                count += 1
            }
            assertTrue(
                "${parent.localName} has $count ${step.name}, expected ${step.min}..${step.max}",
                count in step.min..step.max,
            )
        }
        assertEquals("${parent.localName} has unexpected children", children.size, index)
    }

    private fun expectAtMostOne(
        children: List<Element>,
        start: Int,
        name: String,
        check: (Element) -> Unit,
    ): Int = take(children, start, name, 0, 1, check)

    private fun expectMany(
        children: List<Element>,
        start: Int,
        name: String,
        check: (Element) -> Unit,
    ): Int = take(children, start, name, 0, Int.MAX_VALUE, check)

    private fun take(
        children: List<Element>,
        start: Int,
        name: String,
        min: Int,
        max: Int,
        check: (Element) -> Unit,
    ): Int {
        var index = start
        var count = 0
        while (index < children.size && children[index].localName == name) {
            check(children[index])
            index += 1
            count += 1
        }
        assertTrue(
            "gpx has $count $name, expected $min..$max",
            count in min..max,
        )
        return index
    }

    private fun childElements(parent: Element): List<Element> {
        val result = mutableListOf<Element>()
        for (index in 0 until parent.childNodes.length) {
            val child = parent.childNodes.item(index)
            if (child is Element) {
                result += child
            } else if (child.nodeType != Node.TEXT_NODE && child.nodeType != Node.COMMENT_NODE) {
                fail("${parent.localName} has a ${child.nodeName} node")
            }
        }
        return result
    }

    private fun childElement(
        parent: Element,
        name: String,
    ): Element? = childElements(parent).firstOrNull { it.localName == name }

    private data class Step(
        val name: String,
        val min: Int,
        val max: Int,
        val check: ((Element) -> Unit)? = null,
    )

    private const val XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance"
    private const val XMLNS_NAMESPACE = "http://www.w3.org/2000/xmlns/"
    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0
    private val DECIMAL = Regex("[+-]?((\\d+(\\.\\d*)?)|(\\.\\d+))")
    private val COLOUR = Regex("#[0-9A-Fa-f]{6}")
}
