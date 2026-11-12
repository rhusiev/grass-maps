package nl.r1a.grassmap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * T-0410's static contract: one FileProvider scoped to the share staging
 * directory, and no storage permission anywhere in the manifest.
 */
class TrackExportManifestTest {
    @Test
    fun theShareProviderExposesOnlyTheStagingDirectory() {
        val manifest = document(MANIFEST)
        val providers = manifest.getElementsByTagName("provider")
        val providersByName = (0 until providers.length).map { providers.item(it) as Element }

        val fileProvider =
            providersByName.singleOrNull {
                it.getAttributeNS(ANDROID_NAMESPACE, "name") == FILE_PROVIDER
            }
        assertTrue("the main manifest has no FileProvider", fileProvider != null)
        val provider = requireNotNull(fileProvider)

        assertEquals("\${applicationId}.track.files", provider.getAttributeNS(ANDROID_NAMESPACE, "authorities"))
        assertEquals("false", provider.getAttributeNS(ANDROID_NAMESPACE, "exported"))
        assertEquals("true", provider.getAttributeNS(ANDROID_NAMESPACE, "grantUriPermissions"))

        val metadata = provider.getElementsByTagName("meta-data").item(0) as Element
        assertEquals(FILE_PROVIDER_PATHS, metadata.getAttributeNS(ANDROID_NAMESPACE, "name"))
        assertEquals("@xml/track_file_paths", metadata.getAttributeNS(ANDROID_NAMESPACE, "resource"))
    }

    @Test
    fun theShareProviderPathsNameOnlyTheCacheDirectory() {
        val paths = document(PATHS)
        val children = paths.documentElement.childNodes
        val elements = (0 until children.length).mapNotNull { children.item(it) as? Element }

        assertEquals(listOf("cache-path"), elements.map { it.tagName })
        assertEquals("shared_tracks", elements.single().getAttribute("name"))
        assertEquals("shared-tracks/", elements.single().getAttribute("path"))
    }

    @Test
    fun theMainManifestDeclaresNoStoragePermission() {
        val text = File(MANIFEST).readText()
        val permissions = Regex("""android\.permission\.[A-Z_]+""").findAll(text).map { it.value }.toSet()

        assertTrue(
            "unexpected storage permission: ${permissions.filter { it.contains("STORAGE") || it.contains("MEDIA") }}",
            permissions.none { it.contains("STORAGE") || it.contains("MEDIA") },
        )
        assertFalse(text.contains("MANAGE_EXTERNAL_STORAGE"))
        assertFalse(text.contains("READ_MEDIA_"))
    }

    private fun document(path: String) =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(File(path).readBytes()))

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val FILE_PROVIDER = "nl.r1a.grassmap.app.TrackFileProvider"
        const val FILE_PROVIDER_PATHS = "android.support.FILE_PROVIDER_PATHS"
        const val MANIFEST = "src/main/AndroidManifest.xml"
        const val PATHS = "src/main/res/xml/track_file_paths.xml"
    }
}
