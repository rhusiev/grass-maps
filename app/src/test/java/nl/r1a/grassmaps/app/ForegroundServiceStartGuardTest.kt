package nl.r1a.grassmaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every `startForegroundService` path keeps the promise it makes (T-0638).
 *
 * A JVM test cannot drive the Android service lifecycle, so this guard reads
 * the app sources. A caller must use [ForegroundStartGate], so a stop cannot
 * destroy the service before its start command, and the service it names must
 * call `startForeground` and read the gate after that call.
 */
class ForegroundServiceStartGuardTest {
    @Test
    fun everyStartForegroundServiceCallHasAGateAndAForegroundStart() {
        val callers = mainSources().filter { START_SERVICE in withoutComments(it.readText()) }
        assertTrue("no startForegroundService caller found", callers.isNotEmpty())

        for (caller in callers) {
            val source = caller.readText()
            val code = withoutComments(source)
            val message = caller.name
            assertTrue("$message must use a ForegroundStartGate", "ForegroundStartGate" in code)
            assertTrue("$message must mark the start pending", "gate.starting()" in code)
            assertTrue("$message must defer a stop while starting", "gate.deferStopIfStarting()" in code)

            val service = serviceName(source, message)
            val serviceSource = File(SERVICE_ROOT, "$service.kt").readText()
            assertTrue("$service must call startForeground", "startForeground(" in serviceSource)
            assertTrue("$service must read its start gate", "startGate.reached()" in serviceSource)
        }
    }

    @Test
    fun everyManifestForegroundServiceCallsStartForeground() {
        val services = manifestForegroundServices()

        assertEquals(setOf("DownloadService", "NavigationService"), services.map { it.substringAfterLast('.') }.toSet())
        for (service in services) {
            val simpleName = service.substringAfterLast('.')
            val source = File(SERVICE_ROOT, "$simpleName.kt").readText()
            assertTrue(
                "$simpleName declares a foreground type but never calls startForeground",
                "startForeground(" in source,
            )
        }
    }

    private fun mainSources(): List<File> = File(SOURCE_ROOT).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun withoutComments(source: String): String =
        source
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")

    private fun serviceName(
        source: String,
        caller: String,
    ): String {
        val match = INTENT.find(source) ?: error("$caller starts a service but names no Intent target")
        return match.groupValues[1]
    }

    private fun manifestForegroundServices(): List<String> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(File(MANIFEST))
        val nodes = document.getElementsByTagName("service")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .filter { it.getAttributeNS(ANDROID_NAMESPACE, "foregroundServiceType").isNotEmpty() }
            .map { it.getAttributeNS(ANDROID_NAMESPACE, "name") }
    }

    private companion object {
        const val START_SERVICE = "startForegroundService("
        const val SOURCE_ROOT = "src/main/java"
        const val SERVICE_ROOT = "src/main/java/nl/r1a/grassmaps/app"
        const val MANIFEST = "src/main/AndroidManifest.xml"
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        val INTENT = Regex("""Intent\([^)]*?,\s*([A-Za-z0-9_]+)::class\.java\)""")
    }
}
