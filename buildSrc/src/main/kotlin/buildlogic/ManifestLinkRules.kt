package buildlogic

import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Checks that the merged app manifest still offers itself for the links another
 * app sends (T-0211).
 *
 * The check reads the merged manifest, so a later merge that drops an intent
 * filter fails the build. It mirrors Android's matching: the `<data>` elements
 * of one filter contribute independent scheme, host, port and path lists, and
 * an intent matches when the action, a category and each data attribute match.
 * A device check (`cmd package query-activities`) answers the same question on
 * hardware; this keeps the answer in the offline gate.
 */
object ManifestLinkRules {
    private const val VIEW_ACTION = "android.intent.action.VIEW"
    private const val DEFAULT_CATEGORY = "android.intent.category.DEFAULT"
    private const val BROWSABLE_CATEGORY = "android.intent.category.BROWSABLE"
    private const val GEO_SCHEME = "geo"
    private const val HTTP_SCHEME = "http"
    private const val HTTPS_SCHEME = "https"

    private val OSM_HOSTS =
        setOf(
            "www.openstreetmap.org",
            "openstreetmap.org",
            "osm.org",
            "www.osm.org",
        )

    private val WEB_SCHEMES = setOf(HTTP_SCHEME, HTTPS_SCHEME)

    /** Every finding names the exact filter that is missing. */
    fun check(
        xml: String,
        mainActivity: String,
    ): List<String> {
        val document = parseManifest(xml)
        val findings = mutableListOf<String>()
        val activity =
            document
                .manifestElements()
                .filter { it.tagName == "activity" }
                .firstOrNull { it.androidAttribute("name") == mainActivity }
        if (activity == null) {
            findings += "activity android:name=\"$mainActivity\" is missing from the merged manifest"
            return findings
        }
        val filters = activity.childElements("intent-filter")
        if (!hasFilter(filters, GEO_SCHEME, host = null)) {
            findings += "$mainActivity has no ACTION_VIEW intent-filter for the geo scheme"
        }
        for (host in OSM_HOSTS.sorted()) {
            for (scheme in WEB_SCHEMES.sorted()) {
                if (!hasFilter(filters, scheme, host)) {
                    findings += "$mainActivity has no ACTION_VIEW intent-filter for $scheme://$host"
                }
            }
        }
        return findings
    }

    private fun hasFilter(
        filters: List<Element>,
        scheme: String,
        host: String?,
    ): Boolean = filters.any { filter -> filter.matches(scheme, host) }

    private fun Element.matches(
        scheme: String,
        host: String?,
    ): Boolean {
        if (VIEW_ACTION !in names("action")) {
            return false
        }
        val categories = names("category")
        if (DEFAULT_CATEGORY !in categories || BROWSABLE_CATEGORY !in categories) {
            return false
        }
        val data = childElements("data")
        if (scheme !in data.mapNotNull { it.androidAttribute("scheme") }) {
            return false
        }
        if (host == null) {
            return true
        }
        // Android keeps the scheme and the authority lists separately, so a
        // host in one <data> element matches a scheme from another.
        val hosts = data.mapNotNull { it.androidAttribute("host")?.lowercase() }
        return host in hosts
    }

    private fun Element.names(tagName: String): List<String> =
        childElements(tagName).mapNotNull { it.androidAttribute("name") }
}
