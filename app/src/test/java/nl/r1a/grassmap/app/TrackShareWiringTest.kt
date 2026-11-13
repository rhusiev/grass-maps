package nl.r1a.grassmap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The share-sheet wiring the JVM can reach (T-0416).
 *
 * The intent delivery itself - Android handing `ACTION_SEND` to
 * [MainActivity] and the content resolver serving the bytes - stays a device
 * check over `adb`; the rule, the import and the notice have their own tests.
 * This one holds the manifest filter and the call sites that no behavioural
 * test reaches without an Activity.
 */
class TrackShareWiringTest {
    @Test
    fun theActivityOffersItselfForAGpxShare() {
        val filter =
            elements(activity(), "intent-filter")
                .firstOrNull { names(it, "action").contains(SEND) }
                ?: error("no ACTION_SEND intent-filter")

        assertTrue("DEFAULT category", names(filter, "category").contains(DEFAULT))
        assertEquals(MIME_TYPES, elements(filter, "data").mapNotNull { it.android("mimeType") }.toSet())
    }

    @Test
    fun theActivityReadsAShareAndKeepsTheLinkPathApart() {
        val source = File(MAIN_ACTIVITY_SOURCE).readText()

        val entries = Regex("""if \(!importSharedDocument\(intent\)\)""").findAll(source).count()
        assertEquals("the first intent and a new one take the same path", 2, entries)
        assertTrue("the rule", "TrackShareIntent.documentUri(" in source)
        assertTrue("the reader", "ContentResolverSharedReader(this@MainActivity)" in source)
        assertTrue("stream extra", "Intent.EXTRA_STREAM" in source)
        assertTrue("clip data", "intent.clipData" in source)
        assertTrue("the link path stays", "openLink(intent?.dataString)" in source)
        assertTrue("the link path stays", "openLink(intent.dataString)" in source)
    }

    @Test
    fun theContainerWiresTheImportToTheOneTrackLibrary() {
        val source = File(APP_CONTAINER).readText()

        assertTrue("trackShare", "internal val trackShare: TrackShareImport" in source)
        assertTrue("one library", "TrackShareImport(tracks = { tracks })" in source)
    }

    @Test
    fun theMapDrawsTheNoticeAndOffersToOpenTheTrack() {
        val source = File(MAP_SCREEN).readText()

        assertTrue("collect", "container.trackShare.notice.collectAsState()" in source)
        assertNotNull("notice bar", Regex("TrackImportNoticeBar\\(").find(source))
        assertTrue("open", "container.trackShare.openImported()" in source)
        assertTrue("auto-hide", "container.trackShare.dismiss(share.sequence)" in source)
    }

    @Test
    fun theNoticeStringsExistInEnglishAndUkrainian() {
        val english = strings(ENGLISH)
        val ukrainian = strings(UKRAINIAN)

        for (key in NOTICE_KEYS) {
            assertTrue("$key in English", key in english)
            assertTrue("$key in Ukrainian", key in ukrainian)
        }
    }

    @Test
    fun theAcceptanceProviderIsDebugOnlyAndNotExported() {
        val provider =
            elements(parse(File(DEBUG_MANIFEST)).documentElement, "provider")
                .firstOrNull { it.android("name") == TEST_PROVIDER }
                ?: error("no test provider in the debug manifest")

        assertEquals(TEST_AUTHORITY, provider.android("authorities"))
        assertEquals("false", provider.android("exported"))
        val mainProviders =
            elements(parse(File(MANIFEST)).documentElement, "provider")
                .mapNotNull { it.android("name") }
        assertTrue("a release build carries no test provider", TEST_PROVIDER !in mainProviders)
    }

    private fun activity(): Element =
        elements(manifest(), "activity").firstOrNull { it.android("name") == MAIN_ACTIVITY }
            ?: error("no $MAIN_ACTIVITY")

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
        const val DEBUG_MANIFEST = "src/debug/AndroidManifest.xml"
        const val MAIN_ACTIVITY = "nl.r1a.grassmap.app.MainActivity"
        const val MAIN_ACTIVITY_SOURCE = "src/main/java/nl/r1a/grassmap/app/MainActivity.kt"
        const val MAP_SCREEN = "src/main/java/nl/r1a/grassmap/app/MapScreen.kt"
        const val APP_CONTAINER = "src/main/java/nl/r1a/grassmap/app/AppContainer.kt"
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"

        const val SEND = "android.intent.action.SEND"
        const val DEFAULT = "android.intent.category.DEFAULT"
        const val TEST_PROVIDER = "nl.r1a.grassmap.app.SharedTrackTestProvider"
        const val TEST_AUTHORITY = "nl.r1a.grassmap.share-test"

        val MIME_TYPES =
            setOf(
                "application/gpx+xml",
                "application/octet-stream",
                "application/xml",
                "text/xml",
            )
        val NOTICE_KEYS =
            setOf(
                "track_import_one",
                "track_import_one_unnamed",
                "track_import_many",
                "track_import_skipped_one",
                "track_import_skipped_many",
                "track_import_open",
                "track_import_not_a_track",
                "track_import_unreadable",
            )
    }
}
