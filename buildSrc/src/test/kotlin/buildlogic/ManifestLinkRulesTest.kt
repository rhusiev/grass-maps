package buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link intent filters the merged manifest must keep (T-0211). Removing a
 * filter, the action, or one of the two categories is a finding, because the
 * app would stop being offered for that link.
 */
class ManifestLinkRulesTest {
    @Test
    fun theAppFiltersPassTheCheck() {
        val findings = ManifestLinkRules.check(manifest(), MAIN_ACTIVITY)

        assertEquals(emptyList<String>(), findings)
    }

    @Test
    fun aMissingGeoFilterFails() {
        val findings =
            ManifestLinkRules.check(
                manifest(extraFilters = webFilters()),
                MAIN_ACTIVITY,
            )

        assertTrue(findings.any { it.contains("geo") })
        assertEquals(1, findings.size)
    }

    @Test
    fun noWebFilterFailsForEveryHostAndScheme() {
        val findings = ManifestLinkRules.check(manifest(extraFilters = geoFilter()), MAIN_ACTIVITY)

        assertEquals(OSM_HOSTS.size * SCHEMES.size, findings.size)
        assertTrue(findings.all { it.contains("openstreetmap.org") || it.contains("osm.org") })
    }

    @Test
    fun schemeAndHostInSeparateDataElementsMatchLikeAndroid() {
        val splitFilter =
            """
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                ${CATEGORIES.joinToString("") { "<category android:name=\"$it\" />" }}
                <data android:scheme="geo" />
                ${SCHEMES.joinToString("") { "<data android:scheme=\"$it\" />" }}
                ${OSM_HOSTS.joinToString("") { "<data android:host=\"$it\" />" }}
                ${PATHS.joinToString("") { pathData(it) }}
            </intent-filter>
            """.trimIndent()

        val findings = ManifestLinkRules.check(manifest(extraFilters = splitFilter), MAIN_ACTIVITY)

        assertEquals(emptyList<String>(), findings)
    }

    @Test
    fun aFilterWithoutTheDefaultAndBrowsableCategoriesFails() {
        val categories = listOf("android.intent.category.DEFAULT")

        val findings =
            ManifestLinkRules.check(
                manifest(extraFilters = geoFilter(categories) + webFilters(categories = categories)),
                MAIN_ACTIVITY,
            )

        assertTrue(findings.any { it.contains("geo") })
        assertTrue(findings.any { it.contains("openstreetmap.org") })
    }

    @Test
    fun aFilterWithoutTheViewActionFails() {
        val findings =
            ManifestLinkRules.check(
                manifest(extraFilters = geoFilter(action = "android.intent.action.SEND")),
                MAIN_ACTIVITY,
            )

        assertTrue(findings.any { it.contains("geo") })
    }

    @Test
    fun aMissingActivityFails() {
        val findings = ManifestLinkRules.check(manifest(), "nl.r1a.grassmap.app.Missing")

        assertEquals(listOf("activity android:name=\"nl.r1a.grassmap.app.Missing\" is missing from the merged manifest"), findings)
    }

    @Test
    fun aFilterWithUnrelatedPathsDoesNotOfferTheOpenedPages() {
        val findings =
            ManifestLinkRules.check(
                manifest(extraFilters = geoFilter() + webFilters(paths = listOf(UNRELATED_PATH))),
                MAIN_ACTIVITY,
            )

        assertEquals(OSM_HOSTS.size * SCHEMES.size, findings.size)
        assertTrue(findings.all { it.contains("/node/1") })
    }

    @Test
    fun aPrefixCoversTheElementPagesAndAnExactPathTheRoot() {
        val findings =
            ManifestLinkRules.check(
                manifest(extraFilters = geoFilter() + webFilters(paths = listOf("/", "/go/"))),
                MAIN_ACTIVITY,
            )

        assertTrue(findings.all { it.contains("/node/1") || it.contains("/way/1") || it.contains("/relation/1") })
        assertTrue(findings.none { it.contains("/go/") })
    }

    @Test
    fun aHostThatIsOnlyASuffixDoesNotMatch() {
        val findings =
            ManifestLinkRules.check(
                manifest(extraFilters = geoFilter() + webFilters(hosts = listOf("evil-osm.org"))),
                MAIN_ACTIVITY,
            )

        assertTrue(findings.any { it.contains("openstreetmap.org") })
    }

    /** A root path is exact; every other accepted path is a directory prefix. */
    private fun pathData(path: String): String =
        if (path.endsWith("/") && path != "/") {
            "<data android:pathPrefix=\"$path\" />"
        } else {
            "<data android:path=\"$path\" />"
        }

    private fun geoFilter(
        categories: List<String> = CATEGORIES,
        action: String = "android.intent.action.VIEW",
    ): String =
        """
        <intent-filter>
            <action android:name="$action" />
            ${categories.joinToString("") { "<category android:name=\"$it\" />" }}
            <data android:scheme="geo" />
        </intent-filter>
        """.trimIndent()

    private fun webFilters(
        hosts: List<String> = OSM_HOSTS,
        categories: List<String> = CATEGORIES,
        paths: List<String> = PATHS,
    ): String =
        hosts.joinToString("\n") { host ->
            """
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                ${categories.joinToString("") { "<category android:name=\"$it\" />" }}
                ${SCHEMES.joinToString("") { "<data android:scheme=\"$it\" android:host=\"$host\" />" }}
                ${paths.joinToString("") { pathData(it) }}
            </intent-filter>
            """.trimIndent()
        }

    private fun manifest(extraFilters: String = geoFilter() + webFilters()): String =
        buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"nl.r1a.grassmap\">")
            appendLine("    <application android:allowBackup=\"true\">")
            appendLine("        <activity android:name=\"$MAIN_ACTIVITY\" android:exported=\"true\" android:screenOrientation=\"portrait\">")
            appendLine(extraFilters.prependIndent("            "))
            appendLine("        </activity>")
            appendLine("    </application>")
            appendLine("</manifest>")
        }

    private companion object {
        const val MAIN_ACTIVITY = "nl.r1a.grassmap.app.MainActivity"

        val SCHEMES = listOf("http", "https")
        val OSM_HOSTS = listOf("www.openstreetmap.org", "openstreetmap.org", "osm.org", "www.osm.org")
        val CATEGORIES =
            listOf(
                "android.intent.category.DEFAULT",
                "android.intent.category.BROWSABLE",
            )

        val PATHS = listOf("/", "/node/", "/way/", "/relation/", "/go/")

        const val UNRELATED_PATH = "/search/"
    }
}
