package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.tracks.TrackTrash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Review 4.1's backup half: both rule files carry the user-data stores and
 * nothing else, so region packs, caches and deleted-track trash stay out of a
 * cloud backup and a device-to-device transfer.
 *
 * The paths come from [UserDataLayout] and [TrackTrash], the same constants the
 * stores are wired with, so a store that moves fails this test instead of
 * silently falling out of backup.
 */
class BackupRulesTest {
    @Test
    fun bothRuleFilesIncludeOnlyTheUserDataStores() {
        for (section in ruleSections()) {
            assertEquals("${section.file} <${section.element.tagName}> includes", STORES, includes(section.element))
            assertEquals("${section.file} <${section.element.tagName}> excludes", EXCLUDED, excludes(section.element))
            assertTrue(
                "${section.file} <${section.element.tagName}> names a domain other than the app's files",
                entries(section.element).all { it.domain == FILES_DOMAIN },
            )
        }
    }

    @Test
    fun theIncludedPathsCannotReachRegionPacksCachesOrTrash() {
        for (section in ruleSections()) {
            val included = includes(section.element)
            assertTrue(
                "${section.file} includes a region package",
                included.none { it == REGIONS || it.startsWith("$REGIONS/") },
            )
            assertTrue(
                "${section.file} includes a cache directory",
                included.none { it == CACHE || it.startsWith("$CACHE/") },
            )
        }
    }

    @Test
    fun cloudBackupNeedsAnEncryptionCapableDevice() {
        val cloud = ruleSections().single { it.element.tagName == "cloud-backup" }

        assertEquals("false", cloud.element.getAttribute("disableIfNoEncryptionCapabilities"))
    }

    @Test
    fun theManifestPointsAtBothRuleFiles() {
        val application =
            document(MANIFEST).getElementsByTagName("application").item(0) as Element

        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(ANDROID_NAMESPACE, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", application.getAttributeNS(ANDROID_NAMESPACE, "fullBackupContent"))
    }

    /** The cloud and transfer sections of each file, and the whole full backup. */
    private fun ruleSections(): List<Section> {
        val cloudAndTransfer =
            listOf("cloud-backup", "device-transfer").map { name ->
                Section(DATA_EXTRACTION_RULES, element(DATA_EXTRACTION_RULES, name))
            }
        val fullBackup = document(FULL_BACKUP_CONTENT).documentElement
        return cloudAndTransfer + Section(FULL_BACKUP_CONTENT, fullBackup)
    }

    private fun element(
        path: String,
        tag: String,
    ): Element = document(path).getElementsByTagName(tag).item(0) as Element

    private fun entries(section: Element): List<Rule> {
        val children = section.childNodes
        return (0 until children.length)
            .mapNotNull { children.item(it) as? Element }
            .filter { it.tagName == "include" || it.tagName == "exclude" }
            .map { element ->
                Rule(
                    included = element.tagName == "include",
                    domain = element.getAttribute("domain"),
                    path = element.getAttribute("path"),
                )
            }
    }

    private fun includes(section: Element): Set<String> = entries(section).filter { it.included }.map { it.path }.toSet()

    private fun excludes(section: Element): Set<String> = entries(section).filterNot { it.included }.map { it.path }.toSet()

    private fun document(path: String) =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(File(path).readBytes()))

    private data class Section(
        val file: String,
        val element: Element,
    )

    private data class Rule(
        val included: Boolean,
        val domain: String,
        val path: String,
    )

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val MANIFEST = "src/main/AndroidManifest.xml"
        const val DATA_EXTRACTION_RULES = "src/main/res/xml/data_extraction_rules.xml"
        const val FULL_BACKUP_CONTENT = "src/main/res/xml/backup_rules.xml"
        const val FILES_DOMAIN = "file"
        const val CACHE = "cache"
        val REGIONS = UserDataLayout.REGIONS_DIRECTORY
        val STORES =
            setOf(
                UserDataLayout.FAVOURITES_PATH,
                UserDataLayout.TRACKS_DIRECTORY,
                UserDataLayout.SEARCH_HISTORY_PATH,
                UserDataLayout.ROUTE_HISTORY_PATH,
            )
        val EXCLUDED = setOf("${UserDataLayout.TRACKS_DIRECTORY}/${TrackTrash.DIRECTORY}")
    }
}
