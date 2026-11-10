package nl.r1a.grassmap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The link wiring the JVM can reach (T-0211).
 *
 * The merged-manifest rule in `buildSrc` checks the manifest after merging; this
 * test checks the source manifest and the call sites that no behavioural test
 * can reach without an Activity. The Activity lifecycle itself - Android
 * delivering a second link to [MainActivity.onNewIntent] - stays a device
 * check.
 */
class AppLinkWiringTest {
    @Test
    fun theActivityIsSingleTopSoALinkReachesTheExistingInstance() {
        assertEquals("singleTop", activity().getAttribute("android:launchMode"))
    }

    @Test
    fun theGeoFilterHasTheViewActionAndBothCategories() {
        val filter = filterWithScheme("geo") ?: error("no geo intent-filter")

        assertTrue("VIEW action", names(filter, "action").contains(VIEW))
        assertTrue("DEFAULT category", names(filter, "category").contains(DEFAULT))
        assertTrue("BROWSABLE category", names(filter, "category").contains(BROWSABLE))
    }

    @Test
    fun theWebFilterNamesEveryOsmHostSchemeAndOpenedPath() {
        val data = elements(webFilter() ?: error("no osm.org intent-filter"), "data")

        assertEquals(SCHEMES, data.mapNotNull { it.android("scheme") }.toSet())
        assertEquals(HOSTS, data.mapNotNull { it.android("host") }.toSet())
        assertEquals(setOf("/"), data.mapNotNull { it.android("path") }.toSet())
        assertEquals(PATH_PREFIXES, data.mapNotNull { it.android("pathPrefix") }.toSet())
    }

    @Test
    fun theActivityHandlesBothAFirstIntentAndANewOne() {
        val source = File(MAIN_ACTIVITY_SOURCE).readText()

        assertTrue("onNewIntent", "override fun onNewIntent(intent: Intent)" in source)
        assertTrue("first intent", "openLink(intent?.dataString)" in source)
        assertTrue("new intent", "openLink(intent.dataString)" in source)
        assertTrue("refusal message", "R.string.link_not_opened" in source)
        assertTrue("coordinate message", "R.string.link_without_coordinates" in source)
    }

    @Test
    fun theMapScreenAppliesTheParkedMoveAndReportsAFailure() {
        val source = File(MAP_SCREEN).readText()

        assertTrue("parked move", "container.links.applyPending(surface.host)" in source)
        assertTrue("link flow", "container.links.pendingMoves.collect" in source)
        assertTrue("failed move", "R.string.link_not_opened" in source)
    }

    @Test
    fun theContainerWiresTheRouterToTheSessionAndTheCameraModes() {
        val source = File(APP_CONTAINER).readText()

        assertTrue("MapLinks(mapSession, cameraModes)" in source)
    }

    @Test
    fun theLinkMessagesExistInEnglishAndUkrainian() {
        val english = strings(ENGLISH)
        val ukrainian = strings(UKRAINIAN)

        for (key in LINK_KEYS) {
            assertTrue("$key in English", key in english)
            assertTrue("$key in Ukrainian", key in ukrainian)
        }
    }

    private fun activity(): Element =
        elements(manifest(), "activity").firstOrNull { it.android("name") == MAIN_ACTIVITY }
            ?: error("no $MAIN_ACTIVITY")

    private fun filterWithScheme(scheme: String): Element? =
        elements(activity(), "intent-filter").firstOrNull { filter ->
            elements(filter, "data").any { it.android("scheme") == scheme }
        }

    private fun webFilter(): Element? =
        elements(activity(), "intent-filter").firstOrNull { filter ->
            elements(filter, "data").any { it.android("host") != null }
        }

    private fun names(
        element: Element,
        tag: String,
    ): List<String> = elements(element, tag).mapNotNull { it.android("name") }

    private fun elements(
        element: Element,
        tag: String,
    ): List<Element> {
        val nodes = element.getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.android(name: String): String? = getAttributeNS(ANDROID_NAMESPACE, name).ifEmpty { null }

    private fun manifest(): Element = parse(File(MANIFEST)).documentElement

    private fun strings(path: String): Set<String> =
        Regex("""<string name="([^"]+)">""").findAll(File(path).readText()).map { it.groupValues[1] }.toSet()

    private fun parse(file: File): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(file)
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val MANIFEST = "src/main/AndroidManifest.xml"
        const val MAIN_ACTIVITY = "nl.r1a.grassmap.app.MainActivity"
        const val MAIN_ACTIVITY_SOURCE = "src/main/java/nl/r1a/grassmap/app/MainActivity.kt"
        const val MAP_SCREEN = "src/main/java/nl/r1a/grassmap/app/MapScreen.kt"
        const val APP_CONTAINER = "src/main/java/nl/r1a/grassmap/app/AppContainer.kt"
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"

        const val VIEW = "android.intent.action.VIEW"
        const val DEFAULT = "android.intent.category.DEFAULT"
        const val BROWSABLE = "android.intent.category.BROWSABLE"

        val SCHEMES = setOf("http", "https")
        val HOSTS = setOf("www.openstreetmap.org", "openstreetmap.org", "osm.org", "www.osm.org")
        val PATH_PREFIXES = setOf("/node/", "/way/", "/relation/", "/go/")
        val LINK_KEYS = setOf("link_not_opened", "link_without_coordinates")
    }
}
