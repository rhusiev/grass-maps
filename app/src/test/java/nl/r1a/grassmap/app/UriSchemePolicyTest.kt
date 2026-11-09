package nl.r1a.grassmap.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `ACTION_VIEW` scheme allowlist behind [AndroidPlatformActions.openUri]
 * (T-0210). The decision is pure, so a JVM test pins it without an Android
 * device.
 */
class UriSchemePolicyTest {
    @Test
    fun theCardAndShareSchemesAreAllowedInAnyLetterCase() {
        for (scheme in listOf("http", "https", "tel", "mailto", "geo", "HTTP", "HTTPS", "Geo", "MAILTO")) {
            assertTrue("$scheme must be allowed", isAllowedScheme(scheme))
        }
    }

    @Test
    fun everyOtherSchemeIsRefused() {
        for (scheme in listOf("intent", "file", "content", "javascript", "ftp", "data", "blob", "chrome", "")) {
            assertFalse("$scheme must be refused", isAllowedScheme(scheme))
        }
        assertFalse(isAllowedScheme(null))
    }
}
