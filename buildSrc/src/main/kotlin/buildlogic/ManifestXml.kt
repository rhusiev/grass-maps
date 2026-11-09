package buildlogic

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * The XML helpers the merged-manifest guards share.
 *
 * The parser refuses a doctype and every external entity, so a manifest can
 * never make the build read another file.
 */
internal const val ANDROID_NAMESPACE: String = "http://schemas.android.com/apk/res/android"

internal fun parseManifest(xml: String): Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.isXIncludeAware = false
    factory.isExpandEntityReferences = false
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
}

internal fun Document.manifestElements(): List<Element> {
    val nodes = getElementsByTagName("*")
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}

internal fun Element.childElements(tagName: String): List<Element> {
    val nodes = getElementsByTagName(tagName)
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}

internal fun Element.androidAttribute(name: String): String? =
    getAttributeNS(ANDROID_NAMESPACE, name).ifEmpty { null }
