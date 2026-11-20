package buildlogic

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CheckReleaseApkGuardsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun aDebugControlClassDescriptorInTheApkFails() {
        val apk = temporary.newFile("app-release.apk")
        writeApk(apk, mapOf("classes.dex" to "Lnl/r1a/grassmap/control/ControlServer;"))

        val findings = apkMarkerFindings(apk, debugOnlyApkMarkers)

        assertEquals(1, findings.size)
        assertTrue(findings.single().contains("debug control channel"))
        assertTrue(findings.single().contains("classes.dex"))
    }

    @Test
    fun anApkWithoutDebugControlMarkersHasNoFindings() {
        val apk = temporary.newFile("app-release.apk")
        writeApk(apk, mapOf("classes.dex" to "Lnl/r1a/grassmap/app/GrassMapsApplication;"))

        assertTrue(apkMarkerFindings(apk, debugOnlyApkMarkers).isEmpty())
    }

    @Test
    fun everyTokenFileContributesTheWholeValueAndAPrefix() {
        val first = tokenFile("debug-builds.token", "0123456789abcdef0123456789abcdef")
        val second = tokenFile("owner-phone.token", "fedcba9876543210fedcba9876543210")

        val markers = tokenMarkers(listOf(first, second))

        assertEquals(4, markers.size)
        assertTrue(
            markers.any {
                it.label.contains("debug-builds.token") &&
                    String(it.bytes) == "0123456789abcdef0123456789abcdef"
            },
        )
        assertTrue(
            markers.any {
                it.label.contains("owner-phone.token") && String(it.bytes) == "fedcba987654"
            },
        )
    }

    @Test
    fun aLeakedTokenIsNamedByItsFileAndNeverPrinted() {
        val token = "0123456789abcdef0123456789abcdef"
        val apk = temporary.newFile("app-release.apk")
        writeApk(apk, mapOf("classes.dex" to "prefix $token suffix"))
        val file = tokenFile("debug-builds.token", token)

        val findings = apkMarkerFindings(apk, tokenMarkers(listOf(file)))

        assertEquals(2, findings.size)
        assertTrue(findings.all { it.contains("debug-builds.token") })
        assertFalse(findings.any { it.contains(token) })
        assertFalse(findings.any { it.contains("0123456789") })
    }

    @Test
    fun blankTokenFilesContributeNoMarkers() {
        val file = tokenFile("blank.token", "  \n\n")

        assertTrue(tokenMarkers(listOf(file)).isEmpty())
    }

    private fun tokenFile(
        name: String,
        content: String,
    ): File = temporary.newFile(name).apply { writeText(content) }

    private fun writeApk(
        file: File,
        entries: Map<String, String>,
    ) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
