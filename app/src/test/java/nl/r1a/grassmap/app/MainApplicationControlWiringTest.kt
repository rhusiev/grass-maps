package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugins.dev.tools.DevToolsContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The release APK runs [GrassMapsApplication]; only the debug source set
 * overrides its seams. These defaults therefore keep AI control and the
 * development-tools panel out of a release build even if a debug-only class
 * were somehow packaged.
 */
class MainApplicationControlWiringTest {
    @Test
    fun aiControlStaysOffAndNoPanelIsSupplied() {
        val application = GrassMapsApplication()

        assertFalse(application.aiControlEnabled())
        assertNull(application.devToolsPanel())
        assertFalse(application.switchEnabled()(DevToolsContract.aiControlSwitch))
    }
}
