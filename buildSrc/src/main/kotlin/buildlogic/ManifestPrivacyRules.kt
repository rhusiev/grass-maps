package buildlogic

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Static privacy checks over an Android merged manifest.
 *
 * Every check returns a finding that names the exact offending entry, so the
 * build log says what to remove without a second lookup.
 */
object ManifestPrivacyRules {
    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    private const val BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"
    private const val ACCOUNT_AUTHENTICATOR = "android.accounts.AccountAuthenticator"

    private val ACCOUNT_PERMISSIONS =
        setOf(
            "android.permission.AUTHENTICATE_ACCOUNTS",
            "android.permission.MANAGE_ACCOUNTS",
        )

    private val PERMISSION_TAGS = setOf("uses-permission", "uses-permission-sdk-23")

    fun check(xml: String, mainActivity: String): List<String> {
        val document = parse(xml)
        val findings = mutableListOf<String>()
        checkPermissions(document, findings)
        checkAuthenticators(document, findings)
        checkMainActivity(document, mainActivity, findings)
        checkAllowBackup(document, findings)
        return findings
    }

    private fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    private fun checkPermissions(document: Document, findings: MutableList<String>) {
        elements(document)
            .filter { it.tagName in PERMISSION_TAGS }
            .forEach { permission ->
                val name = permission.androidAttribute("name") ?: return@forEach
                if (name == BACKGROUND_LOCATION) {
                    findings += "${permission.tagName} android:name=\"$name\" (location must stay foreground-only)"
                } else if (name in ACCOUNT_PERMISSIONS) {
                    findings += "${permission.tagName} android:name=\"$name\" (the app requires no account)"
                }
            }
    }

    private fun checkAuthenticators(document: Document, findings: MutableList<String>) {
        elements(document)
            .filter { it.tagName == "service" }
            .filter { service ->
                val declaredName = service.androidAttribute("name").orEmpty()
                declaredName == ACCOUNT_AUTHENTICATOR || service.declaresAuthenticator()
            }.forEach { service ->
                val name = service.androidAttribute("name").orEmpty()
                findings +=
                    "service android:name=\"$name\" declares $ACCOUNT_AUTHENTICATOR (the app requires no account)"
            }
    }

    private fun Element.declaresAuthenticator(): Boolean =
        listOf("action", "meta-data").any { tag ->
            childElements(tag).any { it.androidAttribute("name") == ACCOUNT_AUTHENTICATOR }
        }

    private fun checkMainActivity(
        document: Document,
        mainActivity: String,
        findings: MutableList<String>,
    ) {
        val activity =
            elements(document)
                .filter { it.tagName == "activity" }
                .firstOrNull { it.androidAttribute("name") == mainActivity }
        if (activity == null) {
            findings += "activity android:name=\"$mainActivity\" is missing from the merged manifest"
            return
        }
        val orientation = activity.androidAttribute("screenOrientation")
        if (orientation != "portrait") {
            val shown = orientation ?: "unset"
            findings +=
                "activity android:name=\"$mainActivity\" must set android:screenOrientation=\"portrait\" (found $shown)"
        }
    }

    private fun checkAllowBackup(document: Document, findings: MutableList<String>) {
        val application = elements(document).firstOrNull { it.tagName == "application" }
        if (application == null) {
            findings += "application element is missing from the merged manifest"
            return
        }
        val allowBackup = application.androidAttribute("allowBackup")
        if (allowBackup != "true") {
            val shown = allowBackup ?: "unset"
            findings += "application android:allowBackup must be \"true\" (found $shown)"
        }
    }

    private fun elements(document: Document): List<Element> {
        val nodes = document.getElementsByTagName("*")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.childElements(tagName: String): List<Element> {
        val nodes = getElementsByTagName(tagName)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.androidAttribute(name: String): String? =
        getAttributeNS(ANDROID_NAMESPACE, name).ifEmpty { null }
}
